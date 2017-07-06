package org.red5.sip.app;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.daemon.Daemon;
import org.apache.commons.daemon.DaemonContext;
import org.openmeetings.utils.PropertiesUtils;
import org.red5.sip.net.rtmp.RTMPControlClient;
import org.red5.sip.net.rtmp.RTMPRoomClient;
import org.red5.sip.net.rtp.RTPStreamMultiplexingSender;
import org.red5.sip.net.rtp.RTPStreamSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zoolu.sip.address.NameAddress;

public class Application implements Daemon {
	private static final Logger log = LoggerFactory.getLogger(Application.class);
	private static final int SIP_START_PORT = 5070;
	private static final int SOUND_START_PORT = 3010;
	private static final int VIDEO_START_PORT = 7010;
	private static int sipPort = SIP_START_PORT;
	private static int soundPort = SOUND_START_PORT;
	private static int videoPort = VIDEO_START_PORT;
	private Properties props = null;
	private Map<Long, SIPTransport> transportMap = new HashMap<>();
	private RTMPControlClient rtmpControlClient;
	private String host;
	private String context;
	private String uid;

	private SIPTransport createSIPTransport(Properties prop, long roomId) {
		log.info("Creating SIP trasport for room: " + roomId);
		RTPStreamSender.useASAO = "asao".equals(prop.getProperty("red5.codec"));
		RTMPRoomClient roomClient = new RTMPRoomClient(host, context, uid, roomId);

		SIPTransport sipTransport = new SIPTransport(roomClient, sipPort++, soundPort++, videoPort++) {
			@Override
			public void onUaRegistrationSuccess(SIPRegisterAgent ra, NameAddress target, NameAddress contact, String result) {
				log.info("Registered successfully");
				this.roomClient.setSipNumberListener(this);
				this.roomClient.start();
			}

			@Override
			public void onUaRegistrationFailure(SIPRegisterAgent ra, NameAddress target, NameAddress contact, String result) {
				log.info("Register failure");
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					log.info("Reconnection pause was interrupted");
				}
				this.register();
			}
		};
		sipTransport.login(prop.getProperty("sip.obproxy"), prop.getProperty("sip.phone"),
				prop.getProperty("sip.authid"), prop.getProperty("sip.secret"), prop.getProperty("sip.realm"),
				prop.getProperty("sip.proxy"));
		sipTransport.register();
		return sipTransport;
	}

	public void init(String[] args) {
		log.info("Red5SIP starting...");
		File settings = new File(args[0]);
		if (!settings.exists()) {
			log.error("Settings file " + args[0] + " not found");
			return;
		}
		props = PropertiesUtils.load(settings);
		try {
			host = props.getProperty("red5.host");
			context = props.getProperty("om.context", "openmeetings");
			uid = props.getProperty("uid");

			RTPStreamMultiplexingSender.sampling = RTPStreamMultiplexingSender.SAMPLE_RATE.findByShortName(Integer
					.parseInt(props.getProperty("red5.codec.rate", "22")));
		} catch (NumberFormatException e) {
			log.error("Can't parse red5.codec.rate value", e);
		}

	}

	@Override
	public void init(DaemonContext daemonContext) throws Exception {
		init(daemonContext.getArguments());
	}

	@Override
	public void start() throws Exception {
		String roomsStr = props.getProperty("rooms", null);
		if ("yes".equals(props.getProperty("rooms.forceStart")) && roomsStr != null) {
			String[] rooms = roomsStr.split(",");
			for (String room : rooms) {
				try {
					long id = Long.parseLong(room);
					transportMap.put(id, createSIPTransport(props, id));
				} catch (NumberFormatException e) {
					log.error("Room id parsing error: id=\"" + room + "\"");
				}
			}
		} else {
			this.rtmpControlClient = new RTMPControlClient(host, context) {
				@Override
				protected void startRoomClient(long roomId) {
					transportMap.put(roomId, createSIPTransport(props, roomId));
				}

				@Override
				protected void stopRoomClient(long roomId) {
					SIPTransport t = transportMap.remove(roomId);
					if (t != null) {
						t.close();
					}
				}
			};
			this.rtmpControlClient.start();
		}
	}

	@Override
	public void stop() throws Exception {
		if (this.rtmpControlClient != null) {
			this.rtmpControlClient.stop();
		}
		for (SIPTransport t : transportMap.values()) {
			t.close();
		}
		transportMap.clear();
	}

	@Override
	public void destroy() {
	}
}
