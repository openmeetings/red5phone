package org.red5.sip.app;

import java.net.DatagramSocket;

import org.red5.codecs.SIPCodec;
import org.red5.sip.net.rtp.RTPStreamMultiplexingSender;
import org.red5.sip.net.rtp.RTPStreamReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.ua.MediaLauncher;

public class SIPAudioLauncher implements MediaLauncher {

	protected static Logger log = LoggerFactory.getLogger(SIPAudioLauncher.class);

	DatagramSocket socket = null;

	public IMediaSender sender = null;

	public RTPStreamReceiver receiver = null;

	public SIPAudioLauncher(SIPCodec sipCodec, int localPort, String remoteAddr, int remotePort,
			IMediaReceiver mediaReceiver) {

		try {
			socket = new DatagramSocket(localPort);

			printLog("SIPAudioLauncher", "New audio sender to " + remoteAddr + ":" + remotePort + ".");
			printLog("SIPAudioLauncher", "sender configs: payloadType = [" + sipCodec.getCodecId()
					+ "], payloadName = [" + sipCodec.getCodecName() + "].");

			// sender = new RTPStreamSender( mediaReceiver, false,
			// sipCodec, socket, remoteAddr, remotePort );
			sender = new RTPStreamMultiplexingSender(mediaReceiver, false, sipCodec, socket, remoteAddr, remotePort);

			printLog("SIPAudioLauncher", "New audio receiver on " + localPort + ".");

			receiver = new RTPStreamReceiver(sipCodec, mediaReceiver, socket);
		} catch (Exception e) {
			printLog("SIPAudioLauncher", "Exception " + e);
			log.error("Exception", e);
		}
	}

	@Override
	public boolean startMedia() {

		printLog("startMedia", "Starting sip audio...");

		if (sender != null) {
			printLog("startMedia", "Start sending.");
			sender.start();
		}

		if (receiver != null) {
			printLog("startMedia", "Start receiving.");

			receiver.start();
		}

		return true;
	}

	@Override
	public boolean stopMedia() {

		printLog("stopMedia", "Halting sip audio...");

		if (sender != null) {
			sender.halt();
			sender = null;
			printLog("stopMedia", "Sender halted.");
		}

		if (receiver != null) {
			receiver.halt();
			receiver = null;
			printLog("stopMedia", "Receiver halted.");
		}

		// take into account the resilience of RtpStreamSender
		// (NOTE: it does not take into account the resilience of
		// RtpStreamReceiver; this can cause SocketException)
		try {
			Thread.sleep(RTPStreamReceiver.SO_TIMEOUT);
		} catch (Exception e) {
		}
		socket.close();
		return true;
	}

	private static void printLog(String method, String message) {
		log.debug("SipAudioLauncher - " + method + " -> " + message);
	}
}
