// Calls the published HUSP_SP_Algo exactly as its own test class does (constructor, runAlgo,
// printStatistics); the published test class does not compile (input is declared twice).
public class RunHuspSp {
    public static void main(String[] args) throws Exception {
        HUSP_SP.HUSP_SP_Algo a = new HUSP_SP.HUSP_SP_Algo(args[0], Double.parseDouble(args[1]), "unused");
        a.runAlgo();
        a.printStatistics();
    }
}
