package optimize;

import midend.llvm.IrBuilder;
import midend.llvm.instr.AluInstr;
import midend.llvm.instr.BranchInstr;
import midend.llvm.instr.CallInstr;
import midend.llvm.instr.CompareInstr;
import midend.llvm.instr.ExtendInstr;
import midend.llvm.instr.GepInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.JumpInstr;
import midend.llvm.instr.LoadInstr;
import midend.llvm.instr.MoveInstr;
import midend.llvm.instr.ReturnInstr;
import midend.llvm.instr.StoreInstr;
import midend.llvm.instr.TruncInstr;
import midend.llvm.instr.AllocateInstr;
import midend.llvm.instr.io.GetCharInstr;
import midend.llvm.instr.io.GetIntInstr;
import midend.llvm.instr.io.PrintCharInstr;
import midend.llvm.instr.io.PrintIntInstr;
import midend.llvm.instr.io.PrintStrInstr;
import midend.llvm.instr.phi.ParallelCopyInstr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrFunction;
import midend.llvm.value.IrParameter;
import midend.llvm.value.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

public class InlineFunction extends Optimizer {
    private static final int INLINE_LIMIT = 30;

    @Override
    public void Optimize() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (IrFunction irFunction : irModule.GetFunctions()) {
                for (IrBasicBlock irBasicBlock : irFunction.GetBasicBlocks()) {
                    ArrayList<Instr> instrList = irBasicBlock.GetInstrList();
                    for (int i = 0; i < instrList.size(); i++) {
                        Instr instr = instrList.get(i);
                        if (!(instr instanceof CallInstr callInstr)) {
                            continue;
                        }
                        IrFunction callee = this.ResolveCallee(callInstr.GetTargetFunction());
                        if (!this.CanInline(irFunction, callee, callInstr)) {
                            continue;
                        }
                        if (this.InlineCall(irBasicBlock, callInstr, callee)) {
                            changed = true;
                            break;
                        }
                    }
                    if (changed) {
                        break;
                    }
                }
                if (changed) {
                    break;
                }
            }
        }
    }

    private boolean InlineCall(IrBasicBlock callerBlock, CallInstr callInstr,
                               IrFunction callee) {
        ArrayList<Instr> instrList = callerBlock.GetInstrList();
        int callIndex = instrList.indexOf(callInstr);
        if (callIndex < 0) {
            return false;
        }

        HashMap<IrValue, IrValue> valueMap = new HashMap<>();
        ArrayList<IrValue> callArgs = callInstr.GetParamList();
        ArrayList<IrParameter> params = callee.GetParameterList();
        if (params.size() != callArgs.size()) {
            return false;
        }
        for (int i = 0; i < params.size(); i++) {
            valueMap.put(params.get(i), callArgs.get(i));
        }

        IrBasicBlock calleeBlock = callee.GetBasicBlocks().get(0);
        ArrayList<Instr> newInstrs = new ArrayList<>();
        IrBasicBlock oldBlock = IrBuilder.GetCurrentBasicBlock();
        IrBuilder.SetCurrentBasicBlock(callerBlock);

        IrValue returnValue = null;
        for (Instr instr : calleeBlock.GetInstrList()) {
            if (instr instanceof ReturnInstr returnInstr) {
                IrValue ret = returnInstr.GetReturnValue();
                returnValue = ret == null ? null : this.MapValue(ret, valueMap);
                break;
            }
            IrValue newValue = this.CloneInstr(instr, valueMap, newInstrs, callerBlock);
            if (newValue == null) {
                IrBuilder.SetCurrentBasicBlock(oldBlock);
                return false;
            }
            valueMap.put(instr, newValue);
        }
        IrBuilder.SetCurrentBasicBlock(oldBlock);

        if (returnValue == null && !callInstr.GetIrType().IsVoidType()) {
            return false;
        }

        for (Instr newInstr : newInstrs) {
            instrList.remove(newInstr);
        }
        for (Instr newInstr : newInstrs) {
            instrList.add(callIndex++, newInstr);
        }

        if (!callInstr.GetIrType().IsVoidType()) {
            callInstr.ModifyAllUsersToNewValue(returnValue);
        }
        callInstr.RemoveAllValueUse();
        instrList.remove(callInstr);
        return true;
    }

    private IrValue CloneInstr(Instr instr, HashMap<IrValue, IrValue> valueMap,
                               ArrayList<Instr> newInstrs, IrBasicBlock callerBlock) {
        Instr newInstr = null;
        if (instr instanceof AluInstr aluInstr) {
            IrValue valueL = this.MapValue(aluInstr.GetValueL(), valueMap);
            IrValue valueR = this.MapValue(aluInstr.GetValueR(), valueMap);
            newInstr = new AluInstr(this.GetAluOpString(aluInstr), valueL, valueR);
        } else if (instr instanceof CompareInstr compareInstr) {
            IrValue valueL = this.MapValue(compareInstr.GetValueL(), valueMap);
            IrValue valueR = this.MapValue(compareInstr.GetValueR(), valueMap);
            newInstr = new CompareInstr(this.GetCompareOpString(compareInstr), valueL, valueR);
        } else if (instr instanceof ExtendInstr extendInstr) {
            IrValue origin = this.MapValue(extendInstr.GetOriginValue(), valueMap);
            newInstr = new ExtendInstr(origin, extendInstr.GetTargetType());
        } else if (instr instanceof TruncInstr truncInstr) {
            IrValue origin = this.MapValue(truncInstr.GetOriginValue(), valueMap);
            newInstr = new TruncInstr(origin, truncInstr.GetTargetType());
        } else if (instr instanceof GepInstr gepInstr) {
            IrValue pointer = this.MapValue(gepInstr.GetPointer(), valueMap);
            IrValue offset = this.MapValue(gepInstr.GetOffset(), valueMap);
            newInstr = new GepInstr(pointer, offset);
        } else if (instr instanceof AllocateInstr allocateInstr) {
            newInstr = new AllocateInstr(allocateInstr.GetTargetType());
        } else if (instr instanceof LoadInstr loadInstr) {
            IrValue pointer = this.MapValue(loadInstr.GetPointer(), valueMap);
            newInstr = new LoadInstr(pointer);
        } else if (instr instanceof StoreInstr storeInstr) {
            IrValue value = this.MapValue(storeInstr.GetValueValue(), valueMap);
            IrValue address = this.MapValue(storeInstr.GetAddressValue(), valueMap);
            newInstr = new StoreInstr(value, address);
        } else if (instr instanceof MoveInstr moveInstr) {
            IrValue src = this.MapValue(moveInstr.GetSrcValue(), valueMap);
            IrValue dst = this.MapValue(moveInstr.GetDstValue(), valueMap);
            newInstr = new MoveInstr(src, dst, callerBlock);
        } else if (instr instanceof PrintIntInstr printIntInstr) {
            IrValue value = this.MapValue(printIntInstr.GetPrintValue(), valueMap);
            newInstr = new PrintIntInstr(value);
        } else if (instr instanceof PrintCharInstr printCharInstr) {
            IrValue value = this.MapValue(printCharInstr.GetPrintValue(), valueMap);
            newInstr = new PrintCharInstr(value);
        } else if (instr instanceof PrintStrInstr printStrInstr) {
            newInstr = new PrintStrInstr(printStrInstr.GetConstString());
        } else if (instr instanceof GetIntInstr) {
            newInstr = new GetIntInstr();
        } else if (instr instanceof GetCharInstr) {
            newInstr = new GetCharInstr();
        } else if (instr instanceof CallInstr callInstr) {
            IrFunction callee = this.ResolveCallee(callInstr.GetTargetFunction());
            if (callee == null) {
                return null;
            }
            ArrayList<IrValue> params = new ArrayList<>();
            for (IrValue param : callInstr.GetParamList()) {
                params.add(this.MapValue(param, valueMap));
            }
            newInstr = new CallInstr(callee, params);
        } else {
            return null;
        }
        newInstrs.add(newInstr);
        return newInstr;
    }

    private IrValue MapValue(IrValue value, HashMap<IrValue, IrValue> valueMap) {
        if (valueMap.containsKey(value)) {
            return valueMap.get(value);
        }
        return value;
    }

    private String GetAluOpString(AluInstr aluInstr) {
        return switch (aluInstr.GetAluOp()) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case SDIV -> "/";
            case SREM -> "%";
            case AND -> "&";
            case OR -> "|";
        };
    }

    private String GetCompareOpString(CompareInstr compareInstr) {
        return switch (compareInstr.GetCompareOp()) {
            case EQ -> "==";
            case NE -> "!=";
            case SGT -> ">";
            case SGE -> ">=";
            case SLT -> "<";
            case SLE -> "<=";
        };
    }

    private boolean CanInline(IrFunction caller, IrFunction callee, CallInstr callInstr) {
        if (callee == null || caller == callee) {
            return false;
        }
        if (callee.IsMainFunction()) {
            return false;
        }
        String name = callee.GetIrName();
        if (name.equals("@getint") || name.equals("@putint") ||
            name.equals("@putch") || name.equals("@putstr")) {
            return false;
        }
        if (callee.GetBasicBlocks().size() != 1) {
            return false;
        }
        IrBasicBlock block = callee.GetBasicBlocks().get(0);
        if (this.HasControlFlow(block)) {
            return false;
        }
        if (this.HasDisallowedInstr(block)) {
            return false;
        }
        return this.GetInlineCost(block) <= INLINE_LIMIT;
    }

    private boolean HasControlFlow(IrBasicBlock block) {
        for (Instr instr : block.GetInstrList()) {
            if (instr instanceof BranchInstr || instr instanceof JumpInstr) {
                return true;
            }
        }
        return false;
    }

    private boolean HasDisallowedInstr(IrBasicBlock block) {
        for (Instr instr : block.GetInstrList()) {
            if (instr instanceof PhiInstr || instr instanceof ParallelCopyInstr) {
                return true;
            }
        }
        return false;
    }

    private int GetInlineCost(IrBasicBlock block) {
        int count = 0;
        for (Instr instr : block.GetInstrList()) {
            if (instr instanceof ReturnInstr) {
                continue;
            }
            count++;
        }
        return count;
    }

    private IrFunction ResolveCallee(IrFunction callee) {
        if (callee == null) {
            return null;
        }
        if (irModule.GetFunctions().contains(callee)) {
            return callee;
        }
        for (IrFunction irFunction : irModule.GetFunctions()) {
            if (irFunction.GetIrName().equals(callee.GetIrName())) {
                return irFunction;
            }
        }
        return null;
    }
}
