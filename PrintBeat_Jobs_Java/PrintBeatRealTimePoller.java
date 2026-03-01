import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class PrintBeatRealTimePoller {
    private static final String BASE_URL = "https://printos.api.hp.com/printbeat";
    private static final String API_PATH = "/externalApi/v1/RealTimeData";
    private static final String KEY = envOrDefault("PRINTBEAT_KEY", "");
    private static final String SECRET = envOrDefault("PRINTBEAT_SECRET", "");
    private static final List<String> DEVICES = Arrays.asList("48500695");
    private static final String RESOLUTION = "Day";
    private static final String UNIT_SYSTEM = "Metric";

    public static void main(String[] args) {
        PrintBeatRealTimePoller poller = new PrintBeatRealTimePoller();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(poller::pollOnce, 0, 1, TimeUnit.MINUTES);
    }

    private void pollOnce() {
        try {
            String timestamp = Instant.now().toString();
            String auth = createAuthHeader("GET", API_PATH, timestamp, SECRET, KEY);
            String url = buildUrl();
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("x-hp-hmac-authentication", auth);
            connection.setRequestProperty("x-hp-hmac-date", timestamp);
            connection.setRequestProperty("x-hp-hmac-algorithm", "SHA256");
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readAll(stream);
            List<String> states = extractPressStates(body);
            if (states.isEmpty()) {
                System.out.println("pressState: UNKNOWN");
            } else {
                for (String state : states) {
                    System.out.println("pressState: " + state);
                }
            }
            connection.disconnect();
        } catch (Exception ex) {
            System.out.println("Request failed: " + ex.getMessage());
        }
    }

    private static String buildUrl() {
        String devicesParam = String.join(",", DEVICES);
        String query = "devices=" + encode(devicesParam)
                + "&resolution=" + encode(RESOLUTION)
                + "&unitSystem=" + encode(UNIT_SYSTEM);
        return BASE_URL + API_PATH + "?" + query;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String createAuthHeader(String method, String path, String timestamp, String secret, String key)
            throws Exception {
        String stringToSign = method + " " + path + timestamp;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String signature = toHex(hash);
        return key + ":" + signature;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static List<String> extractPressStates(String body) {
        Pattern pattern = Pattern.compile("\"pressState\"\\s*:\\s*\"(.*?)\"");
        Matcher matcher = pattern.matcher(body);
        List<String> states = new java.util.ArrayList<>();
        while (matcher.find()) {
            states.add(matcher.group(1));
        }
        return states;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
