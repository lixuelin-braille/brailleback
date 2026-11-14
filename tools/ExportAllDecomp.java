import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import java.io.File;
import java.io.PrintWriter;

public class ExportAllDecomp extends GhidraScript {
  @Override
  public void run() throws Exception {
    String outPath = System.getenv("GHIDRA_DECOMP_OUT");
    if (outPath == null || outPath.isEmpty()) {
      outPath = "D:\\tools\\decomp_all.c";
    }

    DecompInterface decompiler = new DecompInterface();
    decompiler.openProgram(currentProgram);

    PrintWriter writer = new PrintWriter(new File(outPath), "UTF-8");
    writer.println("// Program : " + currentProgram.getName());
    writer.println("// Language: " + currentProgram.getLanguageID());
    writer.println("// Functions total: "
        + currentProgram.getFunctionManager().getFunctionCount());
    writer.println();

    FunctionIterator functions =
        currentProgram.getFunctionManager().getFunctions(true);
    int total = 0;
    int completed = 0;
    while (functions.hasNext()) {
      Function function = functions.next();
      total++;
      DecompileResults result =
          decompiler.decompileFunction(function, 120, monitor);
      writer.println("// ===================== " + function.getName()
          + " @ " + function.getEntryPoint() + " =====================");
      if (result != null && result.decompileCompleted()) {
        writer.println(result.getDecompiledFunction().getC());
        completed++;
      } else {
        writer.println("// [decompile failed]");
      }
      writer.println();
    }
    writer.close();
    println("Exported " + completed + "/" + total + " functions to " + outPath);
  }
}
