package optimize;

import midend.llvm.constant.IrConstantInt;
import midend.llvm.instr.AllocateInstr;
import midend.llvm.instr.Instr;
import midend.llvm.instr.LoadInstr;
import midend.llvm.instr.StoreInstr;
import midend.llvm.instr.phi.PhiInstr;
import midend.llvm.use.IrUse;
import midend.llvm.value.IrBasicBlock;
import midend.llvm.value.IrValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Stack;

public class InsertPhi {
    private final AllocateInstr allocateInstr;
    private final IrBasicBlock entryBlock;
    private final HashSet<Instr> defineInstrs;
    private final HashSet<Instr> useInstrs;
    private final ArrayList<IrBasicBlock> defineBlocks;
    private final ArrayList<IrBasicBlock> useBlocks;
    private Stack<IrValue> valueStack;

    public InsertPhi(AllocateInstr allocateInstr, IrBasicBlock entryBlock) {
        this.allocateInstr = allocateInstr;
        this.entryBlock = entryBlock;
        this.defineInstrs = new HashSet<>();
        this.useInstrs = new HashSet<>();
        this.defineBlocks = new ArrayList<>();
        this.useBlocks = new ArrayList<>();
        this.valueStack = new Stack<>();
    }

    public void AddPhi() {
        this.BuildDefineUseRelationship();
        this.InsertPhiToBlock();
        this.ConvertLoadStore(this.entryBlock);
    }

    private void BuildDefineUseRelationship() {
        for (IrUse irUse : this.allocateInstr.GetUseList()) {
            Instr userInstr = (Instr) irUse.GetUser();
            if (userInstr instanceof LoadInstr) {
                this.AddUseInstr(userInstr);
            } else if (userInstr instanceof StoreInstr) {
                this.AddDefineInstr(userInstr);
            }
        }
    }

    private void AddDefineInstr(Instr instr) {
        this.defineInstrs.add(instr);
        if (!this.defineBlocks.contains(instr.GetInBasicBlock())) {
            this.defineBlocks.add(instr.GetInBasicBlock());
        }
    }

    private void AddUseInstr(Instr instr) {
        this.useInstrs.add(instr);
        if (!this.useBlocks.contains(instr.GetInBasicBlock())) {
            this.useBlocks.add(instr.GetInBasicBlock());
        }
    }

    private void InsertPhiToBlock() {
        HashSet<IrBasicBlock> addedPhiBlocks = new HashSet<>();

        Stack<IrBasicBlock> defineBlockStack = new Stack<>();
        for (IrBasicBlock defineBlock : this.defineBlocks) {
            defineBlockStack.push(defineBlock);
        }

        while (!defineBlockStack.isEmpty()) {
            IrBasicBlock defineBlock = defineBlockStack.pop();
            for (IrBasicBlock frontierBlock : defineBlock.GetDominateFrontiers()) {
                if (!addedPhiBlocks.contains(frontierBlock)) {
                    this.InsertPhiInstr(frontierBlock);
                    addedPhiBlocks.add(frontierBlock);
                    if (!this.defineBlocks.contains(frontierBlock)) {
                        defineBlockStack.push(frontierBlock);
                    }
                }
            }
        }
    }

    private void InsertPhiInstr(IrBasicBlock irBasicBlock) {
        PhiInstr phiInstr = new PhiInstr(this.allocateInstr.GetTargetType(), irBasicBlock);
        irBasicBlock.AddInstr(phiInstr, 0);
        this.useInstrs.add(phiInstr);
        this.defineInstrs.add(phiInstr);
    }

    private void ConvertLoadStore(IrBasicBlock renameBlock) {
        final Stack<IrValue> stackCopy = (Stack<IrValue>) this.valueStack.clone();
        this.RemoveBlockLoadStore(renameBlock);
        this.ConvertPhiValue(renameBlock);
        for (IrBasicBlock dominateBlock : renameBlock.GetDirectDominateBlocks()) {
            this.ConvertLoadStore(dominateBlock);
        }
        this.valueStack = stackCopy;
    }

    private void RemoveBlockLoadStore(IrBasicBlock visitBlock) {
        Iterator<Instr> iterator = visitBlock.GetInstrList().iterator();
        while (iterator.hasNext()) {
            Instr instr = iterator.next();
            if (instr instanceof StoreInstr storeInstr && this.defineInstrs.contains(instr)) {
                this.valueStack.push(storeInstr.GetValueValue());
                iterator.remove();
            } else if (!(instr instanceof PhiInstr) && this.useInstrs.contains(instr)) {
                instr.ModifyAllUsersToNewValue(this.PeekValueStack());
                iterator.remove();
            } else if (instr instanceof PhiInstr && this.defineInstrs.contains(instr)) {
                this.valueStack.push(instr);
            } else if (instr == this.allocateInstr) {
                iterator.remove();
            }
        }
    }

    private void ConvertPhiValue(IrBasicBlock visitBlock) {
        for (IrBasicBlock nextBlock : visitBlock.GetNextBlocks()) {
            Instr firstInstr = nextBlock.GetFirstInstr();
            if (firstInstr instanceof PhiInstr phiInstr && this.useInstrs.contains(firstInstr)) {
                phiInstr.ConvertBlockToValue(this.PeekValueStack(), visitBlock);
            }
        }
    }

    private IrValue PeekValueStack() {
        return this.valueStack.isEmpty() ? new IrConstantInt(0) : this.valueStack.peek();
    }
}
