package optimize;

import midend.llvm.instr.Instr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrGlobalValue;
import midend.llvm.value.IrParameter;
import midend.llvm.value.IrValue;

import java.util.ArrayList;
import java.util.HashSet;

public class ActiveAnalysis extends Optimizer {
    @Override
    public void Optimize() {
        this.ClearActiveInfo();
        this.AnalysisDefAndUse();
        this.AnalysisInAndOut();
    }

    private void ClearActiveInfo() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
                irBasicBlock.ClearActiveInfo();
            }
        }
    }

    private void AnalysisDefAndUse() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
                HashSet<IrValue> defSet = irBasicBlock.GetDefValueSet();
                HashSet<IrValue> useSet = irBasicBlock.GetUseValueSet();

                for (Instr instr : irBasicBlock.GetInstrList()) {
                    if (instr instanceof PhiInstr phiInstr) {
                        for (IrValue useValue : phiInstr.GetUseValueList()) {
                            if (this.IsUseValue(useValue)) {
                                useSet.add(useValue);
                            }
                        }
                    }
                }

                for (Instr instr : irBasicBlock.GetInstrList()) {
                    for (IrValue useValue : instr.GetUseValueList()) {
                        if (!defSet.contains(useValue) && this.IsUseValue(useValue)) {
                            useSet.add(useValue);
                        }
                    }
                    if (!useSet.contains(instr) || instr.DefValue()) {
                        defSet.add(instr);
                    }
                }
            }
        }
    }

    private boolean IsUseValue(IrValue useValue) {
        return useValue instanceof Instr ||
            useValue instanceof IrParameter ||
            useValue instanceof IrGlobalValue;
    }

    private void AnalysisInAndOut() {
        for (IrFunction irFunction : irModule.GetFunctions()) {
            ArrayList<IrBasicBlock> blockList = irFunction.GetBasicBlocks();
            boolean haveChange = true;
            while (haveChange) {
                haveChange = false;
                for (int i = blockList.size() - 1; i >= 0; i--) {
                    IrBasicBlock analysisBlock = blockList.get(i);
                    HashSet<IrValue> newOutValueSet = new HashSet<>();
                    for (IrBasicBlock nextBlock : analysisBlock.GetNextBlocks()) {
                        newOutValueSet.addAll(nextBlock.GetInValueSet());
                    }
                    HashSet<IrValue> newInValueSet = new HashSet<>(newOutValueSet);
                    newInValueSet.removeAll(analysisBlock.GetDefValueSet());
                    newInValueSet.addAll(analysisBlock.GetUseValueSet());

                    HashSet<IrValue> oldInValueSet = analysisBlock.GetInValueSet();
                    HashSet<IrValue> oldOutValueSet = analysisBlock.GetOutValueSet();
                    if (!newOutValueSet.equals(oldOutValueSet) ||
                        !newInValueSet.equals(oldInValueSet)) {
                        haveChange = true;
                        analysisBlock.SetInValueSet(newInValueSet);
                        analysisBlock.SetOutValueSet(newOutValueSet);
                    }
                }
            }
        }
    }
}
