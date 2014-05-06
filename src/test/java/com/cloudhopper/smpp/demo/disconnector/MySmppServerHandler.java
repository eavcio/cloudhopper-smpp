package com.cloudhopper.smpp.demo.disconnector;

import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.demo.persist.LoggingUtil;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.SmppProcessingException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MySmppServerHandler implements SmppServerHandler {
	private static final Logger logger = LoggerFactory.getLogger(ServerMain.class);

	private final List<SmppServerSession> smppServerSessions;
	private AtomicInteger atomicInteger;

	public MySmppServerHandler(List<SmppServerSession> smppServerSessions) {
		this.smppServerSessions = smppServerSessions;
		atomicInteger = new AtomicInteger();
	}

	@Override
	public void sessionBindRequested(Long sessionId, SmppSessionConfiguration sessionConfiguration, BaseBind bindRequest) throws SmppProcessingException {
		sessionConfiguration.setName("north." + sessionConfiguration.getSystemId() + "-"
		  + atomicInteger.incrementAndGet());
	}

	@Override
	public void sessionCreated(Long sessionId, final SmppServerSession session, BaseBindResp preparedBindResponse) throws SmppProcessingException {
		session.serverReady(new DefaultSmppSessionHandler(LoggerFactory.getLogger(session.getConfiguration().getSystemId() + "-" + session.getBindType())) {

			public PduResponse firePduRequestReceived(PduRequest pduRequest) {
				return pduRequest.createResponse();
			}

		});
		logger.info("inbound client connected {}", LoggingUtil.toString(session.getConfiguration()));
		smppServerSessions.add(session);
	}

	@Override
	public void sessionDestroyed(Long sessionId, SmppServerSession session) {
		logger.info("destroying session, {}", LoggingUtil.toString(session.getConfiguration()));
		session.destroy();
		smppServerSessions.remove(session);
	}
}
