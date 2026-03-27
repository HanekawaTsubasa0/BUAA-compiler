package midend.symbol;

import error.Error;
import error.ErrorTuple; // Need to ensure this exists or use Error.addError
import java.util.ArrayList;
import java.util.Hashtable;

public class SymbolTable {
    private final int depth;
    public int id; // Scope ID
    private int index;

    private final ArrayList<Symbol> symbolList;
    private final Hashtable<String, Symbol> symbolTable;

    private final SymbolTable fatherTable;
    private final ArrayList<SymbolTable> sonTables;

    public SymbolTable(int depth, int id, SymbolTable fatherTable) {
        this.depth = depth;
        this.id = id;
        this.index = -1;

        this.symbolList = new ArrayList<>();
        this.symbolTable = new Hashtable<>();

        this.fatherTable = fatherTable;
        this.sonTables = new ArrayList<>();
    }

    public int GetDepth() {
        return this.depth;
    }

    public Symbol GetSymbol(String symbolName) {
        return this.symbolTable.get(symbolName);
    }

    public SymbolTable GetFatherTable() {
        return this.fatherTable;
    }

    public void AddSonTable(SymbolTable symbolTable) {
        this.sonTables.add(symbolTable);
    }

    public void AddSymbol(Symbol symbol, int line) {
        String symbolName = symbol.GetSymbolName();
        if (!this.symbolTable.containsKey(symbolName)) {
            this.symbolList.add(symbol);
            this.symbolTable.put(symbolName, symbol);
        }
        else {
            // Error 'b': Redefinition
            Error.getInstance().addError(line, "b");
        }
    }

    public SymbolTable GetNextSonTable() {
        if (index + 1 < sonTables.size()) {
            return this.sonTables.get(++index);
        }
        return null;
    }

    public void ResetIndex() {
        this.index = -1;
        for (SymbolTable son : sonTables) {
            son.ResetIndex();
        }
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        // Sort symbols by definition order? symbolList is ArrayList, so it's insertion order.
        for (Symbol symbol : this.symbolList) {
            if (!symbol.GetSymbolName().equals("getint") && 
                !symbol.GetSymbolName().equals("putint") && 
                !symbol.GetSymbolName().equals("putch") && 
                !symbol.GetSymbolName().equals("putstr") && 
                !symbol.GetSymbolName().equals("main")) {
                stringBuilder.append(this.id + " " + symbol + "\n");
            }
        }

        for (SymbolTable sonTable : this.sonTables) {
            stringBuilder.append(sonTable.toString());
        }

        return stringBuilder.toString();
    }
}
