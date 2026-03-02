import java.io.*;
import java.net.*;
import java.util.*;

public class Sender {

    private InetAddress rcvIP;
    private int rcvDataPort;
    private int senderAckPort;
    private String inputFile;
    private int timeoutMs;
    private int windowSize;
    private boolean isGBN;

    private DatagramSocket socket;
    private List<DSPacket> dataPackets;

    // spec says 3 consecutive timeouts for same base = critical failure
    private static final int MAX_TIMEOUTS = 3;

    @SuppressWarnings("CallToPrintStackTrace")
    public static void main(String[] args) {
        try {
            Sender sender = new Sender(args);
            sender.run();
        } catch (Exception e) {
            System.err.println("sender error: " + e.getMessage());
            // print stack trace is ok, we don't need a logger
            e.printStackTrace();
        }
    }

    public Sender(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6) {
            System.err.println("usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            System.exit(1);
        }

        rcvIP = InetAddress.getByName(args[0]);
        rcvDataPort = Integer.parseInt(args[1]);
        senderAckPort = Integer.parseInt(args[2]);
        inputFile = args[3];
        timeoutMs = Integer.parseInt(args[4]);

        // if window_size provided, we're in gbn mode
        if (args.length == 6) {
            windowSize = Integer.parseInt(args[5]);
            if (windowSize % 4 != 0 || windowSize <= 0 || windowSize > 128) {
                System.err.println("window size must be a positive multiple of 4 and <= 128");
                System.exit(1);
            }
            isGBN = true;
        } else {
            windowSize = 1;
            isGBN = false;
        }

        // sender binds to ack port to receive acks from receiver
        socket = new DatagramSocket(senderAckPort);
        socket.setSoTimeout(timeoutMs);
    }

    private void run() throws Exception {
        // timing starts from sending sot per spec
        long startTime = System.currentTimeMillis();

        try {
            performHandshake();
            buildDataPackets();

            if (dataPackets.isEmpty()) {
                // empty file edge case: eot with seq=1 immediately
                sendEOT(1);
            } else if (isGBN) {
                transferGBN();
            } else {
                transferStopAndWait();
            }
        } finally {
            long endTime = System.currentTimeMillis();
            double totalSeconds = (endTime - startTime) / 1000.0;
            System.out.printf("Total Transmission Time: %.2f seconds%n", totalSeconds);
            socket.close();
        }
    }

    private void performHandshake() throws Exception {
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        int timeoutCount = 0;

        while (true) {
            sendPacket(sot);
            System.out.println("[SEND] SOT Seq=0");

            try {
                DSPacket ack = receivePacket();
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("[RECV] ACK Seq=0 (handshake complete)");
                    return;
                }
            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[TIMEOUT] SOT timeout #" + timeoutCount);
                if (timeoutCount >= MAX_TIMEOUTS) {
                    System.out.println("Unable to transfer file.");
                    socket.close();
                    System.exit(1);
                }
            }
        }
    }

    // reads the entire file and splits it into 124-byte data packets
    // seq numbers start at 1, wrap mod 128
    private void buildDataPackets() throws IOException {
        dataPackets = new ArrayList<>();
        byte[] fileBytes = readFile(inputFile);

        int seq = 1;
        int offset = 0;

        while (offset < fileBytes.length) {
            int chunkSize = Math.min(DSPacket.MAX_PAYLOAD_SIZE, fileBytes.length - offset);
            byte[] chunk = Arrays.copyOfRange(fileBytes, offset, offset + chunkSize);
            dataPackets.add(new DSPacket(DSPacket.TYPE_DATA, seq, chunk));
            seq = (seq + 1) % 128;
            offset += chunkSize;
        }

        System.out.println("[INFO] file loaded: " + fileBytes.length + " bytes, " + dataPackets.size() + " packets");
    }

    private byte[] readFile(String path) throws IOException {
        File f = new File(path);
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int totalRead = 0;
            while (totalRead < data.length) {
                int read = fis.read(data, totalRead, data.length - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
        }
        return data;
    }

    // stop-and-wait: send one packet, wait for its specific ack, repeat
    private void transferStopAndWait() throws Exception {
        for (int i = 0; i < dataPackets.size(); i++) {
            DSPacket pkt = dataPackets.get(i);
            int expectedAckSeq = pkt.getSeqNum();
            int timeoutCount = 0;

            while (true) {
                sendPacket(pkt);
                System.out.println("[SEND] DATA Seq=" + pkt.getSeqNum() + " (" + pkt.getLength() + "B)");

                try {
                    DSPacket ack = receivePacket();
                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == expectedAckSeq) {
                        System.out.println("[RECV] ACK Seq=" + ack.getSeqNum());
                        break; // got what we wanted, move on
                    }
                    // stale or wrong ack, ignore and keep waiting
                    System.out.println("[RECV] unexpected ACK Seq=" + ack.getSeqNum() + " (wanted " + expectedAckSeq + ")");
                } catch (SocketTimeoutException e) {
                    timeoutCount++;
                    System.out.println("[TIMEOUT] DATA Seq=" + pkt.getSeqNum() + " timeout #" + timeoutCount);
                    if (timeoutCount >= MAX_TIMEOUTS) {
                        System.out.println("Unable to transfer file.");
                        socket.close();
                        System.exit(1);
                    }
                }
            }
        }

        // all data sent and acked, time for eot
        int eotSeq = (dataPackets.get(dataPackets.size() - 1).getSeqNum() + 1) % 128;
        sendEOT(eotSeq);
    }

    // gbn: fill the window, wait for acks, retransmit from base on timeout
    private void transferGBN() throws Exception {
        int base = 0;     // index into dataPackets of oldest unacked packet
        int nextIdx = 0;  // index of next packet we haven't sent yet
        int timeoutCount = 0;

        while (base < dataPackets.size()) {
            // send any unsent packets that fit in the window
            int windowEnd = Math.min(base + windowSize, dataPackets.size());
            if (nextIdx < windowEnd) {
                sendGBNBurst(nextIdx, windowEnd);
                nextIdx = windowEnd;
            }

            // try to receive an ack
            try {
                DSPacket ack = receivePacket();
                if (ack.getType() == DSPacket.TYPE_ACK) {
                    int ackSeq = ack.getSeqNum();
                    System.out.println("[RECV] ACK Seq=" + ackSeq);

                    int newBase = advanceBase(base, ackSeq);
                    if (newBase > base) {
                        base = newBase;
                        timeoutCount = 0; // any progress resets timeout counter
                        System.out.println("[INFO] window advanced, base idx=" + base);
                    }
                }
            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[TIMEOUT] base idx=" + base + " timeout #" + timeoutCount);
                if (timeoutCount >= MAX_TIMEOUTS) {
                    System.out.println("Unable to transfer file.");
                    socket.close();
                    System.exit(1);
                }
                // go back n: retransmit entire window starting from base
                nextIdx = base;
            }
        }

        int eotSeq = (dataPackets.get(dataPackets.size() - 1).getSeqNum() + 1) % 128;
        sendEOT(eotSeq);
    }

    // sends packets from startIdx (inclusive) to endIdx (exclusive),
    // applying the chaos engine permutation in groups of 4
    private void sendGBNBurst(int startIdx, int endIdx) throws IOException {
        List<DSPacket> toSend = new ArrayList<>();
        for (int i = startIdx; i < endIdx; i++) {
            toSend.add(dataPackets.get(i));
        }
        if (toSend.isEmpty()) return;

        // permute in groups of 4 per spec, leftover goes in normal order
        List<DSPacket> permuted = new ArrayList<>();
        int i = 0;
        while (i + 4 <= toSend.size()) {
            List<DSPacket> group = new ArrayList<>(toSend.subList(i, i + 4));
            permuted.addAll(ChaosEngine.permutePackets(group));
            i += 4;
        }
        while (i < toSend.size()) {
            permuted.add(toSend.get(i));
            i++;
        }

        for (DSPacket pkt : permuted) {
            sendPacket(pkt);
            System.out.println("[SEND] DATA Seq=" + pkt.getSeqNum() + " (" + pkt.getLength() + "B)");
        }
    }

    // finds where to move base after getting a cumulative ack.
    // only searches within the current window to avoid false matches
    // when seq numbers wrap around on large files
    private int advanceBase(int currentBase, int ackSeq) {
        int windowEnd = Math.min(currentBase + windowSize, dataPackets.size());
        for (int i = currentBase; i < windowEnd; i++) {
            if (dataPackets.get(i).getSeqNum() == ackSeq) {
                return i + 1;
            }
        }
        return currentBase;
    }

    // sends eot and waits for its ack, retransmitting on timeout
    private void sendEOT(int eotSeq) throws Exception {
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
        int timeoutCount = 0;

        while (true) {
            sendPacket(eot);
            System.out.println("[SEND] EOT Seq=" + eotSeq);

            try {
                DSPacket ack = receivePacket();
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                    System.out.println("[RECV] ACK Seq=" + ack.getSeqNum() + " (eot acked)");
                    return;
                }
            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[TIMEOUT] EOT timeout #" + timeoutCount);
                if (timeoutCount >= MAX_TIMEOUTS) {
                    System.out.println("Unable to transfer file.");
                    socket.close();
                    System.exit(1);
                }
            }
        }
    }

    private void sendPacket(DSPacket pkt) throws IOException {
        byte[] data = pkt.toBytes();
        DatagramPacket dgram = new DatagramPacket(data, data.length, rcvIP, rcvDataPort);
        socket.send(dgram);
    }

    private DSPacket receivePacket() throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dgram = new DatagramPacket(buf, buf.length);
        socket.receive(dgram);
        return new DSPacket(dgram.getData());
    }
}