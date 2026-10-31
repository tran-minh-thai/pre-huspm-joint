import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;

/** Launches RunHuspSp under the debugger interface and prints, at every line where the unmodified
 *  HUSP_SP_Algo counts a HUSP, the pattern counted: the field prefix and the local addItem. */
public class WatchBounds {
    public static void main(String[] args) throws Exception {
        LaunchingConnector lc = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> ca = lc.defaultArguments();
        ca.get("main").setValue("RunHuspSp " + args[1] + " " + args[2]);
        ca.get("options").setValue("-cp " + args[0]);
        VirtualMachine vm = lc.launch(ca);
        EventRequestManager erm = vm.eventRequestManager();
        ClassPrepareRequest cpr = erm.createClassPrepareRequest();
        cpr.addClassFilter("HUSP_SP.HUSP_SP_Algo");
        cpr.enable();
        int[] lines = {625};
        int hits = 0;
        loop:
        while (true) {
            EventSet es = vm.eventQueue().remove();
            for (Event e : es) {
                if (e instanceof ClassPrepareEvent) {
                    ReferenceType t = ((ClassPrepareEvent) e).referenceType();
                    for (int l : lines) {
                        for (Location loc : t.locationsOfLine(l)) erm.createBreakpointRequest(loc).enable();
                    }
                } else if (e instanceof BreakpointEvent) {
                    BreakpointEvent b = (BreakpointEvent) e;
                    StackFrame f = b.thread().frame(0);
                    ObjectReference self = f.thisObject();
                    Value minU = self.getValue(self.referenceType().fieldByName("minUtility"));
                    ObjectReference entry = (ObjectReference) f.getValue(f.visibleVariableByName("entry"));
                    ObjectReference boxed = (ObjectReference) entry.getValue(entry.referenceType().fieldByName("key"));
                    Value item = boxed.getValue(boxed.referenceType().fieldByName("value"));
                    ObjectReference last = (ObjectReference) entry.getValue(entry.referenceType().fieldByName("value"));
                    Value swu = last.getValue(last.referenceType().fieldByName("swu"));
                    Value prefix = self.getValue(self.referenceType().fieldByName("prefix"));
                    String p = ((ObjectReference) prefix).invokeMethod(b.thread(),
                            ((ClassType) ((ObjectReference) prefix).referenceType()).concreteMethodByName("toString", "()Ljava/lang/String;"),
                            List.of(), ObjectReference.INVOKE_SINGLE_THREADED).toString();
                    System.out.println("S-extension prefix " + p + " item " + item + " bound " + swu + " minUtility " + minU);
                    hits++;
                } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                    break loop;
                }
            }
            es.resume();
        }
        System.out.println("counted: " + hits);
    }
}
