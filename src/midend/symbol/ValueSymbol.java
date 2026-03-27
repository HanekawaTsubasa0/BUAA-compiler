package midend.symbol;

import java.util.ArrayList;

public class ValueSymbol extends Symbol {
    private final int dimension;
    private final ArrayList<Integer> depthList; // For array dimensions? Or loops?
    // In example: depthList seems to be array dimensions? "int a[2][3]" -> depthList=[2,3]?
    // The example code used "depthList" in constructor.
    
    private boolean isGlobal;
    private boolean isConst;

    public ValueSymbol(String symbolName, SymbolType symbolType) {
        super(symbolName, symbolType);
        this.dimension = 0;
        this.depthList = new ArrayList<>();
        this.isGlobal = false;
        this.isConst = symbolType.toString().startsWith("Const");
    }

    public ValueSymbol(String symbolName, SymbolType symbolType, int dimension) {
        super(symbolName, symbolType);
        this.dimension = dimension;
        this.depthList = new ArrayList<>();
        this.isGlobal = false;
        this.isConst = symbolType.toString().startsWith("Const");
    }
    
    public void SetIsGlobal(boolean isGlobal) {
        this.isGlobal = isGlobal;
    }

    public boolean IsGlobal() {
        return this.isGlobal;
    }

    public int GetDimension() {
        return this.dimension;
    }

    public void SetIsConst(boolean isConst) {
        this.isConst = isConst;
    }

    public boolean IsConst() {
        return this.isConst;
    }
}
