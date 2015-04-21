package com.cloudhopper.smpp.demo.perftest;

/*
 * #%L
 * ch-smpp
 * %%
 * Copyright (C) 2009 - 2015 Cloudhopper by Twitter
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.util.DecimalUtil;
import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.type.*;
import com.cloudhopper.smpp.util.ConcurrentCommandCounter;
import io.netty.channel.nio.NioEventLoopGroup;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.Math.max;

public class PerformanceClientMain2 {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceClientMain2.class);

    //
    // performance testing options (just for this sample)
    //
    // total number of sessions (conns) to create
    static public final int SESSION_COUNT = 60;
    // size of window per session
    static public final int WINDOW_SIZE = 5;

//        static public final ExitCondition EXIT_CONDITION = ExitCondition.totalSubmitSmCount(200000);
    static public final ExitCondition EXIT_CONDITION = ExitCondition.duration(1, TimeUnit.MINUTES);

    static public final int SUBMIT_DELAY = 1;
    static public final boolean DELIVERY_REPORTS = true;
    private static final int TIMEOUT_MILLIS = 10000;


    static public void main(String[] args) throws Exception {
        //
        // setup 3 things required for any session we plan on creating
        //

        // create and assign the NioEventLoopGroup instances to handle event processing,
        // such as accepting new connections, receiving data, writing data, and so on.
        NioEventLoopGroup group = new NioEventLoopGroup();

        // to enable automatic expiration of requests, a second scheduled executor
        // is required which is what a monitor task will be executed with - this
        // is probably a thread pool that can be shared with between all client bootstraps
        ScheduledThreadPoolExecutor monitorExecutor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1, new ThreadFactory() {
            private AtomicInteger sequence = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("SmppClientSessionWindowMonitorPool-" + sequence.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });

        // a single instance of a client bootstrap can technically be shared
        // between any sessions that are created (a session can go to any different
        // number of SMSCs) - each session created under
        // a client bootstrap will use the executor and monitorExecutor set
        // in its constructor - just be *very* careful with the "expectedSessions"
        // value to make sure it matches the actual number of total concurrent
        // open sessions you plan on handling - the underlying netty library
        // used for NIO sockets essentially uses this value as the max number of
        // threads it will ever use, despite the "max pool size", etc. set on
        // the executor passed in here
        DefaultSmppClient clientBootstrap = new DefaultSmppClient(group, monitorExecutor);

        TestState testState = new TestState();

        // create all session runners and executors to run them
        ThreadPoolExecutor taskExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
        ClientSessionTask[] tasks = new ClientSessionTask[SESSION_COUNT];

        for (int i = 0; i < SESSION_COUNT; i++) {
            SmppBindType smppBindType = i % 2 == 0 ? SmppBindType.TRANSMITTER : SmppBindType.RECEIVER;
//            SmppBindType smppBindType = SmppBindType.TRANSCEIVER;
            tasks[i] = new ClientSessionTask(testState, clientBootstrap, getSmppSessionConfiguration(smppBindType));
            taskExecutor.submit(tasks[i]);
        }
        ExecutorService supportExecutor = Executors.newCachedThreadPool();

        try {
            // wait for all sessions to bind
            logger.info("Waiting up to 7 seconds for all sessions to bind...");
            if (testState.allSessionsBoundSignal.await(7000, TimeUnit.MILLISECONDS)) {

                logger.info("Sending signal to start test...");
                testState.startTime = System.currentTimeMillis();
                testState.startSendingSignal.countDown();

                supportExecutor.submit(new DeliveryReceiptReceivingMonitor(testState, tasks));
                supportExecutor.submit(new LoggingTask(tasks));

                taskExecutor.shutdown();
                taskExecutor.awaitTermination(3, TimeUnit.DAYS);

                printStats(tasks, testState);

                logger.info("Done. Exiting");
            } else {
                logger.info("Test failed. Exiting.");
            }
        } finally {
            logger.info("Shutting down client bootstrap and executors...");
            // this is required to not causing server to hang from non-daemon threads
            // this also makes sure all open Channels are closed to I *think*
            supportExecutor.shutdownNow();
            taskExecutor.shutdownNow();
            clientBootstrap.destroy();
            monitorExecutor.shutdownNow();
        }
    }

    private static SmppSessionConfiguration getSmppSessionConfiguration(SmppBindType smppBindType) {
        // same configuration for each client runner
        SmppSessionConfiguration config = new SmppSessionConfiguration();
        config.setWindowSize(WINDOW_SIZE);
        config.setName("Tester.Session.0");
        config.setType(smppBindType);
        config.setHost("127.0.0.1");
        config.setPort(5000);
        config.setConnectTimeout(5000);
        config.setSystemId("1234567890");
        config.setPassword("password");
        config.getLoggingOptions().setLogBytes(false);
        // to enable monitoring (request expiration)
        config.setRequestExpiryTimeout(30000);
        config.setWindowMonitorInterval(15000);
        config.setCountersEnabled(true);
        return config;
    }

    private static void printStats(ClientSessionTask[] tasks, TestState testState) {
        long stopTimeMillisMt = -1;
        for (ClientSessionTask task : tasks) {
            if (task.sendingMtDoneTimestamp != null) {
                stopTimeMillisMt = max(task.sendingMtDoneTimestamp, stopTimeMillisMt);
            }
        }
        long actualSubmitSent = 0;
        long sessionFailures = 0;
        long actualDrReceived = 0;
        long actualSubmitResponseOk = 0;
        long actualSubmitResponseError = 0;
        for (int i = 0; i < SESSION_COUNT; i++) {
            if (tasks[i].getCause() != null) {
                sessionFailures++;
                logger.error("Task #" + i + " failed with exception: " + tasks[i].getCause());
            } else {
                ConcurrentCommandCounter txSubmitSM = tasks[i].counters.getTxSubmitSM();
                actualSubmitSent += txSubmitSM.getRequest();
                actualSubmitResponseOk += max(0, txSubmitSM.getResponseCommandStatusCounter().get(0));
                actualSubmitResponseError += txSubmitSM.getResponse() - max(0, txSubmitSM.getResponseCommandStatusCounter().get(0));
                actualDrReceived += tasks[i].counters.getRxDeliverSM().getRequest();
            }
        }

        logger.info("Performance client finished:");
        logger.info("       Sessions: " + SESSION_COUNT);
        logger.info("    Window Size: " + WINDOW_SIZE);
        logger.info("Sessions Failed: " + sessionFailures);
        logger.info("           Time: " + (stopTimeMillisMt - testState.startTime) / 1000 + " s");
        logger.info("  Actual Submit: " + actualSubmitSent);
        logger.info(" Submit Resp Ok: " + actualSubmitResponseOk);
        logger.info("Submit Resp Err: " + actualSubmitResponseError);
        logger.info("    DR Received: " + actualDrReceived);
        double throughputMt = (double) actualSubmitSent / ((double) (stopTimeMillisMt - testState.startTime) / (double) 1000);
        logger.info("   Throughput MT: " + DecimalUtil.toString(throughputMt, 3) + " per sec");

        for (int i = 0; i < SESSION_COUNT; i++) {
            ClientSessionTask task = tasks[i];
            if (task.counters != null && task.config.getType() != SmppBindType.RECEIVER) {
                logger.info(" Session " + i + ": submitSM {}", task.session.getCounters().getTxSubmitSM());
            }
        }
        for (int i = 0; i < SESSION_COUNT; i++) {
            ClientSessionTask task = tasks[i];
            if (task.counters != null && task.config.getType() != SmppBindType.TRANSMITTER) {
                logger.info(" Session " + i + ": deliverSM {}", task.session.getCounters().getRxDeliverSM());
            }
        }
    }


    public static class ClientSessionTask implements Runnable {

        private SmppSession session;
        private DefaultSmppClient clientBootstrap;
        private SmppSessionConfiguration config;
        private Exception cause;
        protected SmppSessionCounters counters;
        private volatile Long sendingMtDoneTimestamp;
        private TestState testState;

        public ClientSessionTask(TestState testState, DefaultSmppClient clientBootstrap, SmppSessionConfiguration config) {
            this.testState = testState;
            this.clientBootstrap = clientBootstrap;
            this.config = config;
        }

        public Exception getCause() {
            return this.cause;
        }

        @Override
        public void run() {
            // a countdownlatch will be used to eventually wait for all responses
            // to be received by this thread since we don't want to exit too early
            CountDownLatch allSubmitResponseReceivedSignal = new CountDownLatch(1);
            DefaultSmppSessionHandler sessionHandler = new ClientSmppSessionHandler(allSubmitResponseReceivedSignal);

            try {
                // create session a session by having the bootstrap connect a
                // socket, send the bind request, and wait for a bind response
                session = clientBootstrap.bind(config, sessionHandler);
                counters = session.getCounters();

                // don't start sending until signalled
                testState.allSessionsBoundSignal.countDown();
                if (config.getType() == SmppBindType.RECEIVER) {
                    waitForDr();
                } else {
                    startSending();
                    // all threads have sent all submit, we do need to wait for
                    // an acknowledgement for all "inflight" though (synchronize
                    // against the window)
                    logger.debug("before waiting sendWindow.size: {}", session.getSendWindow().getSize());

                    allSubmitResponseReceivedSignal.await();
                    logger.debug("after waiting sendWindow.size: {}", session.getSendWindow().getSize());

                    if (config.getType() == SmppBindType.TRANSCEIVER) {
                        waitForDr();
                    }
                }

                session.unbind(5000);
            } catch (Exception e) {
                testState.allSessionsBoundSignal.fail();
                logger.error("", e);
                this.cause = e;
            }
        }

        private void waitForDr() throws InterruptedException {
            while (session.isBound()) {
                if (testState.stopReceivingSignal.await(5, TimeUnit.SECONDS)) {
                    break;
                }
            }
        }

        private void startSending() throws InterruptedException, RecoverablePduException, UnrecoverablePduException, SmppTimeoutException, SmppChannelException {
            try {
                String text160 = "\u20AC Lorem [ipsum] dolor sit amet, consectetur adipiscing elit. Proin feugiat, leo id commodo tincidunt, nibh diam ornare est, vitae accumsan risus lacus sed sem metus.";
                byte[] textBytes = CharsetUtil.encode(text160, CharsetUtil.CHARSET_GSM);

                testState.startSendingSignal.await();

                // all threads compete for processing
                while (session.isBound() && EXIT_CONDITION.shouldRun(testState)) {
                    SubmitSm submit = new SubmitSm();
                    submit.setSourceAddress(new Address((byte) 0x03, (byte) 0x00, "40404"));
                    submit.setDestAddress(new Address((byte) 0x01, (byte) 0x01, "44555519205"));
                    if (DELIVERY_REPORTS) {
                        submit.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_INTERMEDIATE_NOTIFICATION_REQUESTED);
                    }
                    submit.setShortMessage(textBytes);
                    session.sendRequestPdu(submit, TIMEOUT_MILLIS, false);
                    Thread.sleep(SUBMIT_DELAY);
                }
            } finally {
                sendingMtDoneTimestamp = System.currentTimeMillis();
            }
        }

        class ClientSmppSessionHandler extends DefaultSmppSessionHandler {

            private CountDownLatch allSubmitResponseReceivedSignal;

            public ClientSmppSessionHandler(CountDownLatch allSubmitResponseReceivedSignal) {
                super(logger);
                this.allSubmitResponseReceivedSignal = allSubmitResponseReceivedSignal;
            }

            @Override
            public PduResponse firePduRequestReceived(PduRequest pduRequest) {
                return pduRequest.createResponse();
            }

            @Override
            public void fireChannelUnexpectedlyClosed() {
                // this is an error we didn't really expect for perf testing
                // its best to at least countDown the latch so we're not waiting forever
                logger.error("Unexpected close occurred...");
                this.allSubmitResponseReceivedSignal.countDown();
            }

            @Override
            public void fireExpectedPduResponseReceived(PduAsyncResponse pduAsyncResponse) {
                if (counters.getTxSubmitSM().getResponse() >= counters.getTxSubmitSM().getRequest()) {
                    this.allSubmitResponseReceivedSignal.countDown();
                }
            }
        }
    }

    static class ResultCountDownLatch extends CountDownLatch {
        volatile boolean broken = false;

        public ResultCountDownLatch(int sessionCount) {
            super(sessionCount);
        }

        @Override
        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            boolean await = super.await(timeout, unit);
            return await && !broken;
        }

        public void fail() {
            broken = true;
            while (this.getCount() > 0) {
                this.countDown();
            }

        }

    }

    private static class DeliveryReceiptReceivingMonitor implements Runnable {
        private static final Logger log = LoggerFactory.getLogger(DeliveryReceiptReceivingMonitor.class);

        private TestState testState;
        private ClientSessionTask[] tasks;
        private long lastDrCount;

        public DeliveryReceiptReceivingMonitor(TestState testState, ClientSessionTask[] tasks) {
            this.testState = testState;
            this.tasks = tasks;
        }

        @Override
        public void run() {
            try {
                while (isDr()) {
                    try {
                        Thread.sleep(1000);
                        int drCount = 0;
                        boolean sendingDone = true;
                        for (int i = 0; i < tasks.length; i++) {
                            ClientSessionTask task = tasks[i];
                            drCount += task.counters.getRxDeliverSM().getRequest();
                            if (task.config.getType() != SmppBindType.RECEIVER) {
                                sendingDone = sendingDone && (task.sendingMtDoneTimestamp != null || !task.session.isBound());
                            }
                        }
                        if (sendingDone && lastDrCount - drCount == 0) {
                            logger.info("No more DRs are coming, stop receiving.");
                            testState.stopReceivingSignal.countDown();
                            return;
                        }
                        lastDrCount = drCount;
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            } catch (Exception e) {
                log.error("", e);
            }

        }

        private boolean isDr() {
            return DELIVERY_REPORTS;
        }
    }

    private static class TestState {
        // various latches used to signal when things are ready
        ResultCountDownLatch allSessionsBoundSignal = new ResultCountDownLatch(SESSION_COUNT);
        CountDownLatch startSendingSignal = new CountDownLatch(1);
        CountDownLatch stopReceivingSignal = new CountDownLatch(DELIVERY_REPORTS ? 1 : 0);
        AtomicLong submitSmSentCount = new AtomicLong(0);
        Long startTime;
    }

    private static class LoggingTask implements Runnable {
        private static final Logger log = LoggerFactory.getLogger("perftest.throughput");
        private static final Logger logTotal = LoggerFactory.getLogger("perftest.total");

        private ClientSessionTask[] tasks;
        long lastTotalSubmitSent = 0;
        long lastTotalDrReceived = 0;
        long lastTotalSubmitResponseOk = 0;
        long lastTotalSubmitResponseError = 0;

        public LoggingTask(ClientSessionTask[] tasks) {
            this.tasks = tasks;
        }

        @Override
        public void run() {
            try {
                int j = 0;
                while (true) {
                    long totalSubmitSent = 0;
                    long totalDrReceived = 0;
                    long totalSubmitResponseOk = 0;
                    long totalSubmitResponseError = 0;
                    for (int i = 0; i < SESSION_COUNT; i++) {
                        SmppSessionCounters counters = tasks[i].counters;
                        if (counters != null) {
                            ConcurrentCommandCounter txSubmitSM = counters.getTxSubmitSM();
                            totalSubmitSent += txSubmitSM.getRequest();
                            for (Map.Entry<Integer, Integer> entry : txSubmitSM.getResponseCommandStatusCounter().createSortedMapSnapshot().entrySet()) {
                                if (entry.getKey() == 0) {
                                    totalSubmitResponseOk += entry.getValue();
                                } else {
                                    totalSubmitResponseError += entry.getValue();
                                }
                            }
                            totalDrReceived += counters.getRxDeliverSM().getRequest();
                        }
                    }

                    long sent = totalSubmitSent - lastTotalSubmitSent;
                    long ok = totalSubmitResponseOk - lastTotalSubmitResponseOk;
                    long error = totalSubmitResponseError - lastTotalSubmitResponseError;
                    long dr = totalDrReceived - lastTotalDrReceived;
                    log.info("sent {}, ok {}, error {}, dr {}", sent, ok, error, dr);
                    if (++j % 10 == 0) {
                        logTotal.info("sent {}, ok {}, error {}, dr {}", totalSubmitSent, totalSubmitResponseOk, totalSubmitResponseError, totalDrReceived);
                    }
                    lastTotalSubmitSent = totalSubmitSent;
                    lastTotalSubmitResponseOk = totalSubmitResponseOk;
                    lastTotalSubmitResponseError = totalSubmitResponseError;
                    lastTotalDrReceived = totalDrReceived;

                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.error("", e);
            }
        }
    }

    private static abstract class ExitCondition {
        /**
         * beware, side effect in TotalSubmitSmCondition :(
         */
        public abstract boolean shouldRun(TestState testState);

        public static ExitCondition totalSubmitSmCount(long submitsToSend) {
            return new TotalSubmitSmCondition(submitsToSend);
        }

        public static ExitCondition duration(long duration, TimeUnit unit) {
            return new DurationCondition(duration, unit);
        }

        private static class DurationCondition extends ExitCondition {

            protected Instant testEnd;
            protected long durationMillis;

            public DurationCondition(long duration, TimeUnit unit) {
                durationMillis = TimeUnit.MILLISECONDS.convert(duration, unit);
            }

            @Override
            public boolean shouldRun(TestState testState) {
                if (testEnd == null) { //no need to synchronize
                    testEnd = new Instant(testState.startTime + durationMillis);
                }
                return testEnd.isAfterNow();
            }
        }

        private static class TotalSubmitSmCondition extends ExitCondition {
            private long submitsToSend;

            public TotalSubmitSmCondition(long submitsToSend) {
                this.submitsToSend = submitsToSend;
            }

            @Override
            public boolean shouldRun(TestState testState) {
                return testState.submitSmSentCount.getAndIncrement() < submitsToSend;
            }
        }
    }
}
