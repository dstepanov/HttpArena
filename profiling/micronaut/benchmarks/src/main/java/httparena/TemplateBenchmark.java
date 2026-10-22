package httparena;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
@State(Scope.Thread) @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(3) @Warmup(iterations=3,time=1) @Measurement(iterations=5,time=1)
public class TemplateBenchmark {
    private List<FortunesController.Fortune> rows;
    @Setup public void setup() throws Exception {
        rows = new ArrayList<>(); int id=0;
        for (String message : Files.readAllLines(Path.of(System.getProperty("fortune.messages")))) rows.add(new FortunesController.Fortune(++id,message));
        if (rows.size() != 200) throw new IllegalArgumentException("Expected 200 seeded fortunes");
        rows.add(new FortunesController.Fortune(0,"Additional fortune added at request time."));
        rows.sort(Comparator.comparing(FortunesController.Fortune::message));
        if (!Arrays.equals(before(),after())) throw new AssertionError("UTF-8 output changed");
    }
    @Benchmark public byte[] before() { return views.fortunes.template(rows).render().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    @Benchmark public byte[] after() { return views.fortunes.template(rows).render(com.fizzed.rocker.runtime.ArrayOfByteArraysOutput.FACTORY).toByteArray(); }
}
