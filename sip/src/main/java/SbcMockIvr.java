import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public final class SbcIvrMock {

    private static final int SIP_PORT = 5060;
    private static final int RTP_PORT = 40002;

    private static final int PCMU_PAYLOAD_TYPE = 0;
    private static final int DTMF_PAYLOAD_TYPE = 101;

    private static final int RTP_HEADER_SIZE = 12;
    private static final int SAMPLES_PER_PACKET = 160;

    /*
     * This implementation supports only one active RTP call because it uses
     * a single fixed RTP port: 40002.
     */
    private static volatile boolean mediaSessionActive;

    private static final ExecutorService EXECUTOR =
            Executors.newCachedThreadPool();

    private SbcIvrMock() {
    }

    public static void main(String[] args) {
        String advertisedIp = args.length > 0
                ? args[0]
                : "127.0.0.1";

        System.out.println("Starting Java 17 DTMF IVR mock");
        System.out.println("SIP TCP port: " + SIP_PORT);
        System.out.println("RTP UDP port: " + RTP_PORT);
        System.out.println("Advertised IP: " + advertisedIp);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping IVR mock...");
            EXECUTOR.shutdownNow();
        }));

        try (ServerSocket serverSocket = new ServerSocket(SIP_PORT)) {
            System.out.println("[SIP] Listening on TCP port " + SIP_PORT);

            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = serverSocket.accept();

                EXECUTOR.submit(() ->
                        handleSipConnection(socket, advertisedIp));
            }

        } catch (IOException exception) {
            System.err.println("[SIP] Server stopped: "
                    + exception.getMessage());
        }
    }

    private static void handleSipConnection(
            Socket socket,
            String advertisedIp) {

        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(
                             socket.getInputStream(),
                             StandardCharsets.UTF_8));
             OutputStream output = socket.getOutputStream()) {

            System.out.println("[SIP] Client connected: "
                    + socket.getRemoteSocketAddress());

            SipRequest request = readSipRequest(reader);

            if (request == null) {
                return;
            }

            System.out.println("[SIP] Received: "
                    + request.requestLine());

            if (!request.requestLine().startsWith("INVITE ")) {
                sendSimpleResponse(output, request, 405,
                        "Method Not Allowed");
                return;
            }

            MediaTarget mediaTarget = parseMediaTarget(
                    request.body(),
                    socket.getInetAddress().getHostAddress());

            if (mediaTarget.port() <= 0) {
                sendSimpleResponse(output, request, 400,
                        "Invalid SDP");
                return;
            }

            if (mediaSessionActive) {
                sendSimpleResponse(output, request, 486,
                        "Busy Here");
                return;
            }

            String sdp = createSdp(advertisedIp);

            String response = buildInviteResponse(
                    request,
                    sdp);

            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();

            System.out.println("[SIP] Sent 200 OK");
            System.out.println("[RTP] MicroSIP destination: "
                    + mediaTarget.ip() + ":" + mediaTarget.port());

            mediaSessionActive = true;

            EXECUTOR.submit(() -> {
                try {
                    runMediaSession(
                            mediaTarget.ip(),
                            mediaTarget.port());
                } finally {
                    mediaSessionActive = false;
                }
            });

            /*
             * Keep the TCP connection open and process ACK/BYE.
             * Some SIP clients may open another TCP connection for these.
             */
            processRemainingSipMessages(reader, output);

        } catch (IOException exception) {
            System.err.println("[SIP] Connection error: "
                    + exception.getMessage());
        }
    }

    private static SipRequest readSipRequest(
            BufferedReader reader) throws IOException {

        String requestLine = reader.readLine();

        if (requestLine == null || requestLine.isBlank()) {
            return null;
        }

        Map<String, String> headers = new HashMap<>();

        String line;

        while ((line = reader.readLine()) != null
                && !line.isEmpty()) {

            int colonIndex = line.indexOf(':');

            if (colonIndex > 0) {
                String name = line.substring(0, colonIndex)
                        .trim()
                        .toLowerCase();

                String value = line.substring(colonIndex + 1)
                        .trim();

                headers.put(name, value);
            }
        }

        int contentLength = parseInteger(
                headers.get("content-length"),
                0);

        char[] bodyCharacters = new char[contentLength];
        int totalRead = 0;

        while (totalRead < contentLength) {
            int read = reader.read(
                    bodyCharacters,
                    totalRead,
                    contentLength - totalRead);

            if (read < 0) {
                break;
            }

            totalRead += read;
        }

        String body = new String(
                bodyCharacters,
                0,
                totalRead);

        return new SipRequest(
                requestLine,
                headers,
                body);
    }

    private static void processRemainingSipMessages(
            BufferedReader reader,
            OutputStream output) {

        try {
            while (!Thread.currentThread().isInterrupted()) {
                SipRequest request = readSipRequest(reader);

                if (request == null) {
                    return;
                }

                System.out.println("[SIP] Received: "
                        + request.requestLine());

                if (request.requestLine().startsWith("ACK ")) {
                    System.out.println("[SIP] Call acknowledged");
                } else if (request.requestLine().startsWith("BYE ")) {
                    sendSimpleResponse(
                            output,
                            request,
                            200,
                            "OK");

                    System.out.println("[SIP] Call ended");
                    return;
                }
            }
        } catch (IOException exception) {
            System.out.println("[SIP] Signaling connection closed");
        }
    }

    private static String createSdp(String advertisedIp) {
        return """
                v=0\r
                o=JavaMock 12345 12345 IN IP4 %s\r
                s=Java IVR Mock\r
                c=IN IP4 %s\r
                t=0 0\r
                m=audio %d RTP/AVP 0 101\r
                a=rtpmap:0 PCMU/8000\r
                a=rtpmap:101 telephone-event/8000\r
                a=fmtp:101 0-15\r
                a=sendrecv\r
                """.formatted(
                advertisedIp,
                advertisedIp,
                RTP_PORT);
    }

    private static String buildInviteResponse(
            SipRequest request,
            String sdp) {

        String via = request.header("via");
        String from = request.header("from");
        String to = addToTag(request.header("to"));
        String callId = request.header("call-id");
        String cSeq = request.header("cseq");

        int contentLength =
                sdp.getBytes(StandardCharsets.UTF_8).length;

        return "SIP/2.0 200 OK\r\n"
                + "Via: " + via + "\r\n"
                + "From: " + from + "\r\n"
                + "To: " + to + "\r\n"
                + "Call-ID: " + callId + "\r\n"
                + "CSeq: " + cSeq + "\r\n"
                + "Contact: <sip:ivr@127.0.0.1:"
                + SIP_PORT + ";transport=tcp>\r\n"
                + "Content-Type: application/sdp\r\n"
                + "Content-Length: " + contentLength + "\r\n"
                + "\r\n"
                + sdp;
    }

    private static void sendSimpleResponse(
            OutputStream output,
            SipRequest request,
            int statusCode,
            String reason) throws IOException {

        String response =
                "SIP/2.0 " + statusCode + " " + reason + "\r\n"
                        + "Via: " + request.header("via") + "\r\n"
                        + "From: " + request.header("from") + "\r\n"
                        + "To: " + addToTag(
                                request.header("to")) + "\r\n"
                        + "Call-ID: "
                        + request.header("call-id") + "\r\n"
                        + "CSeq: " + request.header("cseq") + "\r\n"
                        + "Content-Length: 0\r\n"
                        + "\r\n";

        output.write(response.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static MediaTarget parseMediaTarget(
            String sdp,
            String fallbackIp) {

        String mediaIp = fallbackIp;
        int mediaPort = -1;

        for (String rawLine : sdp.split("\\r?\\n")) {
            String line = rawLine.trim();

            if (line.startsWith("c=IN IP4 ")) {
                mediaIp = line.substring("c=IN IP4 ".length())
                        .trim();
            }

            if (line.startsWith("m=audio ")) {
                String[] tokens = line.split("\\s+");

                if (tokens.length >= 2) {
                    mediaPort = parseInteger(tokens[1], -1);
                }
            }
        }

        return new MediaTarget(mediaIp, mediaPort);
    }

    private static void runMediaSession(
            String targetIp,
            int targetPort) {

        StringBuilder inputBuffer = new StringBuilder();

        int sequenceNumber =
                ThreadLocalRandom.current().nextInt(0, 65_536);

        long timestamp =
                ThreadLocalRandom.current().nextLong(
                        0,
                        0x1_0000_0000L);

        int ssrc = ThreadLocalRandom.current().nextInt();
        int activeDtmfEvent = -1;

        try (DatagramSocket udpSocket =
                     new DatagramSocket(RTP_PORT)) {

            udpSocket.setSoTimeout(20);

            InetAddress targetAddress =
                    InetAddress.getByName(targetIp);

            byte[] receiveBuffer = new byte[2048];

            System.out.println("[RTP] Listening on UDP port "
                    + RTP_PORT);
            System.out.println("[IVR] Enter 1234 using MicroSIP");

            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket inboundPacket =
                        new DatagramPacket(
                                receiveBuffer,
                                receiveBuffer.length);

                try {
                    udpSocket.receive(inboundPacket);

                    activeDtmfEvent = processIncomingRtp(
                            inboundPacket,
                            inputBuffer,
                            activeDtmfEvent);

                } catch (SocketTimeoutException ignored) {
                    // Expected every 20 milliseconds.
                }

                byte[] outboundRtp = createSilencePacket(
                        sequenceNumber,
                        timestamp,
                        ssrc);

                DatagramPacket outboundPacket =
                        new DatagramPacket(
                                outboundRtp,
                                outboundRtp.length,
                                targetAddress,
                                targetPort);

                udpSocket.send(outboundPacket);

                sequenceNumber =
                        (sequenceNumber + 1) & 0xFFFF;

                timestamp =
                        (timestamp + SAMPLES_PER_PACKET)
                                & 0xFFFF_FFFFL;
            }

        } catch (IOException exception) {
            System.err.println("[RTP] Media session closed: "
                    + exception.getMessage());
        }
    }

    private static int processIncomingRtp(
            DatagramPacket packet,
            StringBuilder inputBuffer,
            int activeDtmfEvent) {

        byte[] data = packet.getData();
        int offset = packet.getOffset();
        int length = packet.getLength();

        if (length < RTP_HEADER_SIZE) {
            return activeDtmfEvent;
        }

        int payloadType = data[offset + 1] & 0x7F;

        if (payloadType != DTMF_PAYLOAD_TYPE) {
            return activeDtmfEvent;
        }

        int payloadOffset = findRtpPayloadOffset(
                data,
                offset,
                length);

        if (payloadOffset < 0
                || payloadOffset + 4 > offset + length) {
            return activeDtmfEvent;
        }

        int eventId = data[payloadOffset] & 0xFF;

        boolean endOfEvent =
                (data[payloadOffset + 1] & 0x80) != 0;

        if (!endOfEvent && eventId != activeDtmfEvent) {
            String digit = dtmfEventToDigit(eventId);

            if (digit != null) {
                inputBuffer.append(digit);

                System.out.println(
                        "[DTMF] Key: " + digit
                                + " | Input: " + inputBuffer);

                evaluateInput(inputBuffer);
            }

            return eventId;
        }

        if (endOfEvent && eventId == activeDtmfEvent) {
            return -1;
        }

        return activeDtmfEvent;
    }

    private static int findRtpPayloadOffset(
            byte[] data,
            int packetOffset,
            int packetLength) {

        int firstByte = data[packetOffset] & 0xFF;
        int csrcCount = firstByte & 0x0F;
        boolean extensionPresent = (firstByte & 0x10) != 0;

        int payloadOffset =
                packetOffset + RTP_HEADER_SIZE + csrcCount * 4;

        int packetEnd = packetOffset + packetLength;

        if (payloadOffset > packetEnd) {
            return -1;
        }

        if (extensionPresent) {
            if (payloadOffset + 4 > packetEnd) {
                return -1;
            }

            int extensionLengthWords =
                    ((data[payloadOffset + 2] & 0xFF) << 8)
                            | (data[payloadOffset + 3] & 0xFF);

            payloadOffset += 4 + extensionLengthWords * 4;
        }

        return payloadOffset <= packetEnd
                ? payloadOffset
                : -1;
    }

    private static void evaluateInput(
            StringBuilder inputBuffer) {

        if ("1234".contentEquals(inputBuffer)) {
            System.out.println();
            System.out.println(
                    "========================================");
            System.out.println(
                    "SUCCESS: USER ENTERED 1234");
            System.out.println(
                    "========================================");
            System.out.println();

            inputBuffer.setLength(0);
            return;
        }

        if (inputBuffer.length() >= 4) {
            System.out.println(
                    "[IVR] Invalid input. Please try again.");

            inputBuffer.setLength(0);
        }
    }

    private static byte[] createSilencePacket(
            int sequenceNumber,
            long timestamp,
            int ssrc) {

        byte[] packet =
                new byte[RTP_HEADER_SIZE + SAMPLES_PER_PACKET];

        packet[0] = (byte) 0x80;
        packet[1] = (byte) PCMU_PAYLOAD_TYPE;

        packet[2] =
                (byte) ((sequenceNumber >>> 8) & 0xFF);

        packet[3] =
                (byte) (sequenceNumber & 0xFF);

        packet[4] =
                (byte) ((timestamp >>> 24) & 0xFF);

        packet[5] =
                (byte) ((timestamp >>> 16) & 0xFF);

        packet[6] =
                (byte) ((timestamp >>> 8) & 0xFF);

        packet[7] =
                (byte) (timestamp & 0xFF);

        packet[8] =
                (byte) ((ssrc >>> 24) & 0xFF);

        packet[9] =
                (byte) ((ssrc >>> 16) & 0xFF);

        packet[10] =
                (byte) ((ssrc >>> 8) & 0xFF);

        packet[11] =
                (byte) (ssrc & 0xFF);

        /*
         * 0xFF represents approximate silence in G.711 μ-law.
         */
        Arrays.fill(
                packet,
                RTP_HEADER_SIZE,
                packet.length,
                (byte) 0xFF);

        return packet;
    }

    private static String dtmfEventToDigit(int eventId) {
        return switch (eventId) {
            case 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 ->
                    Integer.toString(eventId);
            case 10 -> "*";
            case 11 -> "#";
            case 12 -> "A";
            case 13 -> "B";
            case 14 -> "C";
            case 15 -> "D";
            default -> null;
        };
    }

    private static String addToTag(String toHeader) {
        if (toHeader == null) {
            return "";
        }

        if (toHeader.toLowerCase().contains(";tag=")) {
            return toHeader;
        }

        return toHeader + ";tag=java-ivr";
    }

    private static int parseInteger(
            String value,
            int defaultValue) {

        if (value == null) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private record SipRequest(
            String requestLine,
            Map<String, String> headers,
            String body) {

        private String header(String name) {
            return headers.getOrDefault(
                    name.toLowerCase(),
                    "");
        }
    }

    private record MediaTarget(
            String ip,
            int port) {
    }
}