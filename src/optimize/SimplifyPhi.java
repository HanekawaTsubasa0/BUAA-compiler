package optimize;

import midend.llvm.instr.Instr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrValue;

import java.util.ArrayList;

public class SimplifyPhi extends Optimizer {
    @Override
    public void Optimize() {
        for (IrFunction function : irModule.GetFunctions()) {
            SimplifyInFunction(function);
        }
    }

    private void SimplifyInFunction(IrFunction function) {
        for (IrBasicBlock block : function.GetBasicBlocks()) {
            ArrayList<Instr> instrs = block.GetInstrList();
            for (int i = 0; i < instrs.size(); i++) {
                Instr instr = instrs.get(i);
                if (!(instr instanceof PhiInstr phiInstr)) {
                    continue;
                }
                IrValue replacement = GetSingleValue(phiInstr);
                if (replacement == null) {
                    continue;
                }
                phiInstr.ModifyAllUsersToNewValue(replacement);
                phiInstr.RemoveAllValueUse();
                instrs.remove(i);
                i--;
            }
        }
    }

    private IrValue GetSingleValue(PhiInstr phiInstr) {
        ArrayList<IrValue> values = phiInstr.GetUseValueList();
        if (values.isEmpty()) {
            return null;
        }
        IrValue first = null;
        for (IrValue value : values) {
            if (value == null) {
                return null;
            }
            if (first == null) {
                first = value;
                continue;
            }
            if (!IsSameValue(first, value)) {
                return null;
            }
        }
        return first;
    }

    private boolean IsSameValue(IrValue left, IrValue right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.GetIrName().equals(right.GetIrName());
    }
}
