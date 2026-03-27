package optimize;
import Utils.Setting;

import midend.llvm.IrModule;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;

import java.util.ArrayList;

public class OptimizeManager {
    private static ArrayList<Optimizer> optimizerList;
    private static ArrayList<Optimizer> finalOptimizerList;

    public static void Init(IrModule module) {
        Optimizer.SetIrModule(module);
        optimizerList = new ArrayList<>();
        finalOptimizerList = new ArrayList<>();
        optimizerList.add(new RemoveUnReachCode());
        optimizerList.add(new CfgBuilder());
        optimizerList.add(new RemoveDeadCode());
        optimizerList.add(new CfgBuilder());
        optimizerList.add(new MemToReg());
        optimizerList.add(new CfgBuilder());
        optimizerList.add(new InlineFunction());
        optimizerList.add(new CfgBuilder());
        optimizerList.add(new ConstPropLoop());
        optimizerList.add(new Licm());
        optimizerList.add(new LoopUnroll());
        optimizerList.add(new CfgBuilder());
        optimizerList.add(new ConstPropLoop());
        optimizerList.add(new StrengthReduce());
        optimizerList.add(new Lvn());
        optimizerList.add(new CallCse());
        optimizerList.add(new LoadCse());
        optimizerList.add(new FunctionAttrOpt());
        optimizerList.add(new DeadStoreElim());
        optimizerList.add(new SimplifyPhi());
        optimizerList.add(new RemoveDeadCode());
        optimizerList.add(new CfgBuilder());
        finalOptimizerList.add(new ActiveAnalysis());
        finalOptimizerList.add(new AllocateRegister());
        finalOptimizerList.add(new RemovePhi());
    }

    public static void Optimize() {
        if (!Setting.OPTIMIZE) {
            return;
            }
        long prevCount = CountInstrs();
        while (true) {
            for (Optimizer optimizer : optimizerList) {
                optimizer.Optimize();
            }
            long currentCount = CountInstrs();
            if (currentCount >= prevCount) {
                break;
            }
            prevCount = currentCount;
        }
        for (Optimizer optimizer : finalOptimizerList) {
            optimizer.Optimize();
        }
    }

    private static long CountInstrs() {
        long count = 0;
        for (IrFunction function : Optimizer.irModule.GetFunctions()) {
            for (IrBasicBlock block : function.GetBasicBlocks()) {
                count += block.GetInstrList().size();
            }
        }
        return count;
    }
}
