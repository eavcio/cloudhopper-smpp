package com.cloudhopper.smpp.demo.disconnector;

import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.demo.persist.LoggingUtil;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerMain {

	private static final Logger logger = LoggerFactory.getLogger(ServerMain.class);


	public static void main(String[] args) throws Exception {
		//
		// setup 3 things required for a server
		//

		// for monitoring thread use, it's preferable to create your own instance
		// of an executor and cast it to a ThreadPoolExecutor from Executors.newCachedThreadPool()
		// this permits exposing things like executor.getActiveCount() via JMX possible
		// no point renaming the threads in a factory since underlying Netty 
		// framework does not easily allow you to customize your thread names
		ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();

		// to enable automatic expiration of requests, a second scheduled executor
		// is required which is what a monitor task will be executed with - this
		// is probably a thread pool that can be shared with between all client bootstraps
		ScheduledThreadPoolExecutor monitorExecutor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1, new ThreadFactory() {
			private AtomicInteger sequence = new AtomicInteger(0);

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r);
				t.setName("SmppServerSessionWindowMonitorPool-" + sequence.getAndIncrement());
				return t;
			}
		});

		// create a server configuration
		SmppServerConfiguration configuration = new SmppServerConfiguration();
		configuration.setPort(5495);
		configuration.setMaxConnectionSize(20);
		configuration.setNonBlockingSocketsEnabled(true);
		configuration.setDefaultRequestExpiryTimeout(30000);
		configuration.setDefaultWindowMonitorInterval(15000);
		configuration.setDefaultWindowSize(5);
		configuration.setDefaultWindowWaitTimeout(configuration.getDefaultRequestExpiryTimeout());
		configuration.setDefaultSessionCountersEnabled(true);
		configuration.setJmxEnabled(false);

		final List<SmppServerSession> smppServerSessions = new CopyOnWriteArrayList<SmppServerSession>();
		// create a server, start it up
		DefaultSmppServer smppServer = new DefaultSmppServer(configuration, new MySmppServerHandler(smppServerSessions), executor, monitorExecutor);

		logger.info("Starting SMPP server...");
		smppServer.start();
		logger.info("SMPP server started");

		while (true) {
			System.out.println("Press any key to disconnect transmitters");
			System.in.read();

			logger.info("disconnecting transmitters [totalSessionsSize={}]", smppServerSessions.size());
			for (SmppServerSession session : smppServerSessions) {
				if (session.getBindType() == SmppBindType.TRANSMITTER) {
					logger.info("unbinding {}", LoggingUtil.toString(session.getConfiguration()));
					session.unbind(10000);
				}
			}
		}

		//			logger.info("Stopping SMPP server...");
		//			disconnector.shutdownNow();
		//			smppServer.destroy();
		//			monitorExecutor.shutdown();
		//			logger.info("SMPP server stopped");
		//
		//			logger.info("Server counters: {}", smppServer.getCounters());
	}

}
