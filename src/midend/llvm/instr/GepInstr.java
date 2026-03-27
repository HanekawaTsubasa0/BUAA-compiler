package midend.llvm.instr;

import backend.mips.Register;
import backend.mips.assembly.MipsAlu;
import midend.llvm.constant.IrConstant;
import midend.llvm.type.IrArrayType;
import midend.llvm.type.IrPointerType;
import midend.llvm.type.IrType;
import midend.llvm.value.IrValue;

public class GepInstr extends Instr {
    public GepInstr(IrValue pointer, IrValue offset) {
        super(new IrPointerType(GetTargetType(pointer)), InstrType.GEP);
        this.AddUseValue(pointer);
        this.AddUseValue(offset);
    }

    public IrValue GetPointer() {
        return this.useValueList.get(0);
    }

    public IrValue GetOffset() {
        return this.useValueList.get(1);
    }

    @Override
    public boolean DefValue() {
        return true;
    }

    @Override
    public String GetGvnHash() {
        return this.GetPointer().GetIrName() + " " + this.GetOffset().GetIrName();
    }

    @Override
    public String toString() {
        IrValue pointer = this.GetPointer();
        IrValue offset = this.GetOffset();

        IrPointerType pointerType = (IrPointerType) pointer.GetIrType();
        IrType targetType = pointerType.GetTargetType();

        if (targetType.IsArrayType()) {
            IrArrayType arrayType = (IrArrayType) targetType;
            return this.irName + " = getelementptr inbounds " +
                arrayType + ", " +
                pointerType + " " +
                pointer.GetIrName() + ", i32 0, " +
                offset.GetIrType() + " " +
                offset.GetIrName();
        } else {
            return this.irName + " = getelementptr inbounds " +
                targetType + ", " +
                pointerType + " " +
                pointer.GetIrName() + ", " +
                offset.GetIrType() + " " +
                offset.GetIrName();
        }
    }

    @Override
    public void toMips() {
        super.toMips();

        IrValue pointerValue = this.GetPointer();
        IrValue offsetValue = this.GetOffset();

        if (offsetValue instanceof IrConstant irConstant) {
            Register pointerRegister = this.GetRegisterOrK0ForValue(pointerValue);
            Register targetRegister = this.GetRegisterOrK1ForValue(this);
            this.LoadValueToRegister(pointerValue, pointerRegister);
            new MipsAlu(MipsAlu.AluType.ADDIU, targetRegister, pointerRegister,
                Integer.parseInt(irConstant.GetIrName()) << 2);
            this.SaveRegisterResult(this, targetRegister);
        } else {
            Register baseRegister = Register.K0;
            Register indexRegister = Register.K1;
            this.LoadValueToRegister(pointerValue, baseRegister);
            this.LoadValueToRegister(offsetValue, indexRegister);
            new MipsAlu(MipsAlu.AluType.SLL, indexRegister, indexRegister, 2);
            new MipsAlu(MipsAlu.AluType.ADDU, baseRegister, baseRegister, indexRegister);
            this.SaveRegisterResult(this, baseRegister);
        }
    }

    public static IrType GetTargetType(IrValue pointer) {
        IrType targetType = ((IrPointerType) pointer.GetIrType()).GetTargetType();
        if (targetType instanceof IrArrayType arrayType) {
            return arrayType.GetElementType();
        } else if (targetType instanceof IrPointerType pointerType) {
            return pointerType.GetTargetType();
        } else {
            return targetType;
        }
    }
}
