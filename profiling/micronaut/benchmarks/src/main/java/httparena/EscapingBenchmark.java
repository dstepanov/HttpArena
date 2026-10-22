package httparena;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
@State(Scope.Thread) @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(3) @Warmup(iterations=3,time=1) @Measurement(iterations=5,time=1)
public class EscapingBenchmark {
    private List<String> messages;
    @Setup public void setup() throws Exception {
        messages = new ArrayList<>(Files.readAllLines(Path.of(System.getProperty("fortune.messages"))));
        if (messages.size() != 200) throw new IllegalArgumentException("Expected 200 seeded fortunes");
        messages.add("Additional fortune added at request time.");
        messages.sort(Comparator.naturalOrder());
        for (String text : messages) {
            StringBuilder before = new StringBuilder(), after = new StringBuilder();
            beforeEscape(before,text); afterEscape(after,text);
            if (!before.toString().equals(after.toString())) throw new AssertionError(text);
        }
    }
    @Benchmark public StringBuilder before() {
        StringBuilder out = new StringBuilder(32 * 1024);
        for (String text : messages) beforeEscape(out, text);
        return out;
    }
    @Benchmark public StringBuilder after() {
        StringBuilder out = new StringBuilder(32 * 1024);
        for (String text : messages) afterEscape(out, text);
        return out;
    }
    private static void beforeEscape(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
    }
    static void afterEscape(StringBuilder out, String text) {
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            String replacement = switch (text.charAt(i)) {
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '&' -> "&amp;";
                case '"' -> "&quot;";
                case '\'' -> "&#39;";
                default -> null;
            };
            if (replacement != null) {
                out.append(text, start, i).append(replacement);
                start = i + 1;
            }
        }
        out.append(text, start, text.length());
    }
}
