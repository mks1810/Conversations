package eu.siacs.conversations.generator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlMessage;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import eu.siacs.conversations.xmpp.jid.Jid;
import eu.siacs.conversations.xmpp.stanzas.MessagePacket;

public class MessageGenerator extends AbstractGenerator {
	private static final String OMEMO_FALLBACK_MESSAGE = "I sent you an OMEMO encrypted message but your client doesn’t seem to support that. Find more information on https://conversations.im/omemo";
	private static final String PGP_FALLBACK_MESSAGE = "I sent you a PGP encrypted message but your client doesn’t seem to support that.";

	public MessageGenerator(XmppConnectionService service) {
		super(service);
	}

	private MessagePacket preparePacket(Message message) {
		Conversation conversation = message.getConversation();
		Account account = conversation.getAccount();
		MessagePacket packet = new MessagePacket();
		final boolean isWithSelf = conversation.getContact().isSelf();
		if (conversation.getMode() == Conversation.MODE_SINGLE) {
			packet.setTo(message.getCounterpart());
			packet.setType(MessagePacket.TYPE_CHAT);
			if (this.mXmppConnectionService.indicateReceived() && !isWithSelf) {
				packet.addChild("request", "urn:xmpp:receipts");
			}
		} else if (message.getType() == Message.TYPE_PRIVATE) {
			packet.setTo(message.getCounterpart());
			packet.setType(MessagePacket.TYPE_CHAT);
			packet.addChild("x","http://jabber.org/protocol/muc#user");
			if (this.mXmppConnectionService.indicateReceived()) {
				packet.addChild("request", "urn:xmpp:receipts");
			}
		} else {
			packet.setTo(message.getCounterpart().toBareJid());
			packet.setType(MessagePacket.TYPE_GROUPCHAT);
		}
		if (conversation.isSingleOrPrivateAndNonAnonymous() && message.getType() != Message.TYPE_PRIVATE) {
			packet.addChild("markable", "urn:xmpp:chat-markers:0");
		}
		packet.setFrom(account.getJid());
		packet.setId(message.getUuid());
		packet.addChild("origin-id", Namespace.STANZA_IDS).setAttribute("id",message.getUuid());
		if (message.edited()) {
			packet.addChild("replace","urn:xmpp:message-correct:0").setAttribute("id",message.getEditedId());
		}
		return packet;
	}

	public void addDelay(MessagePacket packet, long timestamp) {
		final SimpleDateFormat mDateFormat = new SimpleDateFormat(
				"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
		mDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		Element delay = packet.addChild("delay", "urn:xmpp:delay");
		Date date = new Date(timestamp);
		delay.setAttribute("stamp", mDateFormat.format(date));
	}

	public MessagePacket generateAxolotlChat(Message message, XmppAxolotlMessage axolotlMessage) {
		MessagePacket packet = preparePacket(message);
		if (axolotlMessage == null) {
			return null;
		}
		packet.setAxolotlMessage(axolotlMessage.toElement());
		if (Config.supportUnencrypted() && !recipientSupportsOmemo(message)) {
			packet.setBody(OMEMO_FALLBACK_MESSAGE);
		}
		packet.addChild("store", "urn:xmpp:hints");
		packet.addChild("encryption","urn:xmpp:eme:0")
				.setAttribute("name","OMEMO")
				.setAttribute("namespace",AxolotlService.PEP_PREFIX);
		return packet;
	}

	public MessagePacket generateKeyTransportMessage(Jid to, XmppAxolotlMessage axolotlMessage) {
		MessagePacket packet = new MessagePacket();
		packet.setType(MessagePacket.TYPE_CHAT);
		packet.setTo(to);
		packet.setAxolotlMessage(axolotlMessage.toElement());
		packet.addChild("store", "urn:xmpp:hints");
		return packet;
	}

	private static boolean recipientSupportsOmemo(Message message) {
		Contact c = message.getContact();
		return c != null && c.getPresences().allOrNonSupport(AxolotlService.PEP_DEVICE_LIST_NOTIFY);
	}

	public static void addMessageHints(MessagePacket packet) {
		packet.addChild("private", "urn:xmpp:carbons:2");
		packet.addChild("no-copy", "urn:xmpp:hints");
		packet.addChild("no-permanent-store", "urn:xmpp:hints");
		packet.addChild("no-permanent-storage", "urn:xmpp:hints"); //do not copy this. this is wrong. it is *store*
	}

	public MessagePacket generateChat(Message message) {
		MessagePacket packet = preparePacket(message);
		String content;
		if (message.hasFileOnRemoteHost()) {
			Message.FileParams fileParams = message.getFileParams();
			content = fileParams.url.toString();
			packet.addChild("x",Namespace.OOB).addChild("url").setContent(content);
		} else {
			content = message.getBody();
		}
		packet.setBody(content);
		return packet;
	}

	public MessagePacket generatePgpChat(Message message) {
		MessagePacket packet = preparePacket(message);
		if (message.hasFileOnRemoteHost()) {
			final String url = message.getFileParams().url.toString();
			packet.setBody(url);
			packet.addChild("x",Namespace.OOB).addChild("url").setContent(url);
		} else {
			if (Config.supportUnencrypted()) {
				packet.setBody(PGP_FALLBACK_MESSAGE);
			}
			if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
				packet.addChild("x", "jabber:x:encrypted").setContent(message.getEncryptedBody());
			} else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
				packet.addChild("x", "jabber:x:encrypted").setContent(message.getBody());
			}
			packet.addChild("encryption", "urn:xmpp:eme:0")
					.setAttribute("namespace", "jabber:x:encrypted");
		}
		return packet;
	}

	public MessagePacket generateChatState(Conversation conversation) {
		final Account account = conversation.getAccount();
		MessagePacket packet = new MessagePacket();
		packet.setType(conversation.getMode() == Conversation.MODE_MULTI ? MessagePacket.TYPE_GROUPCHAT : MessagePacket.TYPE_CHAT);
		packet.setTo(conversation.getJid().toBareJid());
		packet.setFrom(account.getJid());
		packet.addChild(ChatState.toElement(conversation.getOutgoingChatState()));
		packet.addChild("no-store", "urn:xmpp:hints");
		packet.addChild("no-storage", "urn:xmpp:hints"); //wrong! don't copy this. Its *store*
		return packet;
	}

	public MessagePacket confirm(final Account account, final Jid to, final String id, final Jid counterpart, final boolean groupChat) {
		MessagePacket packet = new MessagePacket();
		packet.setType(groupChat ? MessagePacket.TYPE_GROUPCHAT : MessagePacket.TYPE_CHAT);
		packet.setTo(groupChat ? to.toBareJid() : to);
		packet.setFrom(account.getJid());
		Element displayed = packet.addChild("displayed","urn:xmpp:chat-markers:0");
		displayed.setAttribute("id", id);
		if (groupChat && counterpart != null) {
			displayed.setAttribute("sender",counterpart.toPreppedString());
		}
		packet.addChild("store", "urn:xmpp:hints");
		return packet;
	}

	public MessagePacket conferenceSubject(Conversation conversation,String subject) {
		MessagePacket packet = new MessagePacket();
		packet.setType(MessagePacket.TYPE_GROUPCHAT);
		packet.setTo(conversation.getJid().toBareJid());
		Element subjectChild = new Element("subject");
		subjectChild.setContent(subject);
		packet.addChild(subjectChild);
		packet.setFrom(conversation.getAccount().getJid().toBareJid());
		return packet;
	}

	public MessagePacket directInvite(final Conversation conversation, final Jid contact) {
		MessagePacket packet = new MessagePacket();
		packet.setType(MessagePacket.TYPE_NORMAL);
		packet.setTo(contact);
		packet.setFrom(conversation.getAccount().getJid());
		Element x = packet.addChild("x", "jabber:x:conference");
		x.setAttribute("jid", conversation.getJid().toBareJid().toString());
		String password = conversation.getMucOptions().getPassword();
		if (password != null) {
			x.setAttribute("password",password);
		}
		return packet;
	}

	public MessagePacket invite(Conversation conversation, Jid contact) {
		MessagePacket packet = new MessagePacket();
		packet.setTo(conversation.getJid().toBareJid());
		packet.setFrom(conversation.getAccount().getJid());
		Element x = new Element("x");
		x.setAttribute("xmlns", "http://jabber.org/protocol/muc#user");
		Element invite = new Element("invite");
		invite.setAttribute("to", contact.toBareJid().toString());
		x.addChild(invite);
		packet.addChild(x);
		return packet;
	}

	public MessagePacket received(Account account, MessagePacket originalMessage, ArrayList<String> namespaces, int type) {
		MessagePacket receivedPacket = new MessagePacket();
		receivedPacket.setType(type);
		receivedPacket.setTo(originalMessage.getFrom());
		receivedPacket.setFrom(account.getJid());
		for(String namespace : namespaces) {
			receivedPacket.addChild("received", namespace).setAttribute("id", originalMessage.getId());
		}
		receivedPacket.addChild("store", "urn:xmpp:hints");
		return receivedPacket;
	}

	public MessagePacket received(Account account, Jid to, String id) {
		MessagePacket packet = new MessagePacket();
		packet.setFrom(account.getJid());
		packet.setTo(to);
		packet.addChild("received","urn:xmpp:receipts").setAttribute("id",id);
		packet.addChild("store", "urn:xmpp:hints");
		return packet;
	}
}
