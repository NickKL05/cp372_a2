import java.io.*;
import java.net.*;
import java.util.*;

public class Receiver {

    private InetAddress senderIP;
    private int senderAckPort;
    private int rcvDataPort;
    private String outputFile;
    private int rn;

    private DatagramSocket socket;
    private int expectedSeq;
    private int ackCount; // tracks how many acks we've intended to send (for chaosengine)
    private Map<Integer, byte[]> receiveBuffer; // holds out-of-order packets
    private FileOutputStream fos;

    @SuppressWarnings("CallToPrintStackTrace")
    public static void main(String[] args) {
        try {
            Receiver receiver = new Receiver(args);
            receiver.run();
        } catch (Exception e) {
            System.err.println("receiver error: " + e.getMessage());
            // print stack trace is acceptable here, no logger is necessary
            e.printStackTrace();
        }
    }

    public Receiver(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
            System.exit(1);
        }

        senderIP = InetAddress.getByName(args[0]);
        senderAckPort = Integer.parseInt(args[1]);
        rcvDataPort = Integer.parseInt(args[2]);
        outputFile = args[3];
        rn = Integer.parseInt(args[4]);

        // receiver listens for data on this port
        socket = new DatagramSocket(rcvDataPort);
        expectedSeq = 0;
        ackCount = 0;
        receiveBuffer = new HashMap<>();
    }

    private void run() throws Exception {
        try {
            waitForHandshake();

            fos = new FileOutputStream(outputFile);
            expectedSeq = 1; // first data packet is seq=1

            receiveDataLoop();
        } finally {
            if (fos != null) fos.close();
            socket.close();
        }
    }

    // blocks until we get a valid sot, then acks it
    private void waitForHandshake() throws Exception {
        System.out.println("[INFO] waiting for SOT on port " + rcvDataPort + "...");

        while (true) {
            DSPacket pkt = receivePacket();
            if (pkt.getType() == DSPacket.TYPE_SOT && pkt.getSeqNum() == 0) {
                System.out.println("[RECV] SOT Seq=0");
                sendACK(0);
                System.out.println("[INFO] handshake complete");
                return;
            }
        }
    }

    // main loop: processes data packets and handles eot teardown.
    // also handles duplicate sots in case our sot ack was lost.
    private void receiveDataLoop() throws Exception {
        while (true) {
            DSPacket pkt = receivePacket();

            if (pkt.getType() == DSPacket.TYPE_EOT) {
                System.out.println("[RECV] EOT Seq=" + pkt.getSeqNum());
                sendACK(pkt.getSeqNum());
                System.out.println("[INFO] file transfer complete, closing");
                return;
            }

            if (pkt.getType() == DSPacket.TYPE_SOT) {
                // sender must have retransmitted sot because our ack got lost
                System.out.println("[RECV] duplicate SOT, re-acking");
                sendACK(0);
                continue;
            }

            if (pkt.getType() == DSPacket.TYPE_DATA) {
                int seq = pkt.getSeqNum();
                System.out.println("[RECV] DATA Seq=" + seq + " (" + pkt.getLength() + "B)");
                handleDataPacket(pkt);
            }
        }
    }

    // handles incoming data with buffering for out-of-order packets.
    // always sends a cumulative ack for the highest contiguous delivered seq.
    private void handleDataPacket(DSPacket pkt) throws Exception {
        int seq = pkt.getSeqNum();

        if (seq == expectedSeq) {
            // in order, write it and flush any consecutive buffered packets
            writePayload(pkt);
            expectedSeq = (expectedSeq + 1) % 128;

            while (receiveBuffer.containsKey(expectedSeq)) {
                byte[] buffered = receiveBuffer.remove(expectedSeq);
                fos.write(buffered);
                System.out.println("[DELIVER] buffered Seq=" + expectedSeq);
                expectedSeq = (expectedSeq + 1) % 128;
            }

            // cumulative ack for the last packet we delivered in order
            int ackSeq = (expectedSeq - 1 + 128) % 128;
            sendACK(ackSeq);

        } else if (isWithinWindow(seq)) {
            // out of order but within our window, buffer it
            if (!receiveBuffer.containsKey(seq)) {
                receiveBuffer.put(seq, pkt.getPayload());
                System.out.println("[BUFFER] Seq=" + seq);
            } else {
                System.out.println("[DUP] Seq=" + seq + " already buffered");
            }
            // still send cumulative ack for last in-order
            int ackSeq = (expectedSeq - 1 + 128) % 128;
            sendACK(ackSeq);

        } else {
            // outside window (old duplicate or way too far ahead), discard
            System.out.println("[DISCARD] Seq=" + seq + " outside window");
            int ackSeq = (expectedSeq - 1 + 128) % 128;
            sendACK(ackSeq);
        }
    }

    // checks if seq falls within [expectedSeq, expectedSeq + windowSize) mod 128.
    // the receiver doesn't get the window size as an arg, so we use a generous
    // range of 127 which safely covers all valid window sizes. for stop-and-wait
    // this still works fine since only expectedSeq itself will be in-order.
    private boolean isWithinWindow(int seq) {
        int diff = (seq - expectedSeq + 128) % 128;
        // diff 0 = expectedSeq itself (handled in the if above)
        // diff 1..126 = within our generous window
        // diff 127 = one behind expectedSeq, so that's an old dup
        return diff > 0 && diff < 127;
    }

    // sends an ack, but chaosengine might drop it
    private void sendACK(int seq) throws IOException {
        ackCount++;

        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("[DROP] ACK Seq=" + seq + " (ackCount=" + ackCount + ", RN=" + rn + ")");
            return;
        }

        DSPacket ackPkt = new DSPacket(DSPacket.TYPE_ACK, seq, null);
        byte[] data = ackPkt.toBytes();
        DatagramPacket dgram = new DatagramPacket(data, data.length, senderIP, senderAckPort);
        socket.send(dgram);
        System.out.println("[SEND] ACK Seq=" + seq);
    }

    private void writePayload(DSPacket pkt) throws IOException {
        if (pkt.getLength() > 0) {
            fos.write(pkt.getPayload());
            System.out.println("[WRITE] Seq=" + pkt.getSeqNum() + " (" + pkt.getLength() + "B)");
        }
    }

    private DSPacket receivePacket() throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dgram = new DatagramPacket(buf, buf.length);
        socket.receive(dgram);
        return new DSPacket(dgram.getData());
    }
}