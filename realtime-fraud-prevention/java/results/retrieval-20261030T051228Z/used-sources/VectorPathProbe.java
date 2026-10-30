package dev.infrahack.fraud;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.BreakpointRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Observe real Lucene branches outside timed requests. Detach before measuring latency. */
final class VectorPathProbe implements AutoCloseable {
    private final VirtualMachine vm;
    private final List<BreakpointRequest> requests = new ArrayList<>();
    private final Thread reader;
    final AtomicLong approximate = new AtomicLong(), exact = new AtomicLong();
    private volatile boolean running = true;

    VectorPathProbe(int port) throws Exception {
        var connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var args = connector.defaultArguments();
        args.get("hostname").setValue("127.0.0.1");
        args.get("port").setValue(Integer.toString(port));
        VirtualMachine attached = null;
        // JDWP reopens its listening socket asynchronously after the previous observer detaches.
        for (int attempt = 0; attached == null; attempt++) {
            try { attached = connector.attach(args); }
            catch (java.io.IOException e) { if (attempt == 49) throw e; Thread.sleep(100); }
        }
        vm = attached;
        // Method-entry instrumentation deoptimizes far more code and distorted the large exact control.
        // Break only at the concrete branch methods, outside the timed portion of the benchmark.
        for (var type : vm.allClasses()) if (type.name().endsWith("Query")) for (var method : type.methods()) {
            if (!method.isAbstract() && !method.isNative() && (method.name().equals("approximateSearch") || method.name().equals("exactSearch"))) {
                var request = vm.eventRequestManager().createBreakpointRequest(method.location());
                request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                request.enable(); requests.add(request);
            }
        }
        if (requests.size() < 2) throw new IllegalStateException("Lucene branch methods are not loaded");
        reader = Thread.ofPlatform().daemon().start(() -> {
            while (running) {
                try {
                    var events = vm.eventQueue().remove(200);
                    if (events == null) continue;
                    for (var event : events) if (event instanceof BreakpointEvent entry) {
                        if (entry.location().method().name().equals("approximateSearch")) approximate.incrementAndGet();
                        if (entry.location().method().name().equals("exactSearch")) exact.incrementAndGet();
                    }
                    events.resume();
                } catch (Exception e) { if (running) throw new RuntimeException(e); }
            }
        });
    }

    public void close() throws Exception {
        requests.forEach(BreakpointRequest::disable);
        running = false;
        reader.join(1_000);
        vm.dispose();
    }
}
