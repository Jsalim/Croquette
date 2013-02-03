package controllers;

import java.util.UUID;

import models.Conversation;
import models.Conversations;
import models.Message;
import models.Message.Action;

import org.codehaus.jackson.JsonNode;
import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.MessageListener;

import play.Logger;
import play.libs.F.Callback;
import play.libs.F.Callback0;
import play.mvc.Controller;
import play.mvc.Http.Context;
import play.mvc.Result;
import play.mvc.WebSocket;
import views.html.mainView;

import com.google.common.base.Optional;

public class Application extends Controller {

	public static Result index() {
		if (User.getUserUUID().isPresent()) {
			return ok(mainView.render("main"));
		}
		return redirect("/authenticate");
	}

	public static WebSocket<JsonNode> chatSocket() {
		// we need the http context in the websocket handler
		final Context ctx = ctx();

		return new WebSocket<JsonNode>() {

			private XMPPConnectionHandler con;

			public void onReady(WebSocket.In<JsonNode> in, final WebSocket.Out<JsonNode> out) {

				in.onMessage(new Callback<JsonNode>() {
					public void invoke(JsonNode newMessage) {
						Logger.info(" -> New SMS : " + newMessage);

						// restore http context
						Context.current.set(ctx);

						// and save message
						Message message = new Message(newMessage);
						if (message.messageToSend()) {
							UUID userUUID = User.getUserUUID().get();
							saveMessage(userUUID, message.getRecipient(), message);
							// and send it with xmpp
							con.sendMessage(message);
						}
					}
				});

				in.onClose(new Callback0() {
					public void invoke() {
						Logger.info(" -> Websocket closed");
						// we do not want to keep the listeners since we are
						// going to create new ones
						con.deleteAliveListeners();
					}
				});

				handleIncomingSMS(out);
			}

			private void saveMessage(UUID userUUID, String contactPhoneNum, Message message) {
				Conversations conversations = Sessions.getUserConversations(userUUID);
				Conversation conversation = conversations.getConversation(contactPhoneNum);

				conversation.addMessage(message);
				Logger.info("saving message...");
			}

			/*
			 * 
			 */
			private void handleIncomingSMS(WebSocket.Out<JsonNode> out) {
				// restore http context
				Context.current.set(ctx);
				UUID userUUID = User.getUserUUID().get();
				// init the xmpp connection
				con = initXMPPConnectionHandler(userUUID);
				// get the chat so that we can create a xmpp listener
				Chat chat = con.getChat();

				// on new incoming sms, save it and send it to the client
				createSMSListener(out, userUUID, chat);
			}

			/**
			 * create the xmmp connection for the given user
			 */
			private XMPPConnectionHandler initXMPPConnectionHandler(UUID userUUID) {
				String emailAddress = Sessions.getUserEmailAddress(userUUID).get();
				String accessToken = Sessions.getUserToken(userUUID).get();

				XMPPConnectionHandler connection = new XMPPConnectionHandler(emailAddress, accessToken);
				connection.init();
				return connection;
			}

			/**
			 *  
			 */
			private void createSMSListener(final WebSocket.Out<JsonNode> out, final UUID userUUID, Chat chat) {
				chat.addMessageListener(new MessageListener() {
					@Override
					public void processMessage(Chat arg0, org.jivesoftware.smack.packet.Message xmppMsg) {
						Message msg = new Message(xmppMsg);
						// if message received is originally an sms, handle it!
						if (msg.isNewIncomingSMS()) {
							saveMessage(userUUID, msg.getAuthorPhoneNumber(), msg);
							// finally notify the client a new sms has arrived
							notifyNewMessage(out, msg);
						}
					}
				});
			}

			/**
			 * 
			 */
			public void notifyNewMessage(WebSocket.Out<JsonNode> out, Message newMsg) {
				out.write(newMsg.asJson());
			}

		};
	}

	/*
	 * sign off the user by cleaning caches, user sessions and disconnecting
	 * user from its gmail account
	 */
	public static Result SignOff() {
		Optional<UUID> userUUID = User.getUserUUID();
		Sessions.removeUserData(userUUID.get());

		session().clear();

		Logger.info("-> user logged out");
		return redirect("/authenticate");
	}

	public static Result getConversation(String phoneNumber) {
		UUID userUUID = User.getUserUUID().get();
		Conversations conversations = Sessions.getUserConversations(userUUID);
		Conversation conversation = conversations.getConversation(phoneNumber);
		Logger.info(conversations.size() + "");

		return ok(conversation.getConversationAsJson());
	}
}
