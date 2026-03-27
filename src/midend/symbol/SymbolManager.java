package midend.symbol;

public class SymbolManager {
    private static SymbolTable rootSymbolTable;
    private static SymbolTable currentSymbolTable;
    private static int depth;
    private static int tableCounter = 0;

    // Semantic Context
    private static int forDepth = 0;
    private static SymbolType currentFuncType = null;

    public static void Init() {
        depth = 1;
        tableCounter = 1;
        rootSymbolTable = new SymbolTable(depth, tableCounter, null);
        currentSymbolTable = rootSymbolTable;
        forDepth = 0;
        currentFuncType = null;
        
        initLibraryFunctions();
    }

    private static void initLibraryFunctions() {
        // int getint()
        FuncSymbol getint = new FuncSymbol("getint", SymbolType.INT_FUNC);
        currentSymbolTable.AddSymbol(getint, 0);
    }

    public static boolean IsGlobal() {
        return currentSymbolTable == rootSymbolTable;
    }

    public static void AddSymbol(Symbol symbol, int line) {
        currentSymbolTable.AddSymbol(symbol, line);
        if (symbol instanceof ValueSymbol) {
             ((ValueSymbol) symbol).SetIsGlobal(IsGlobal());
        }
    }

    public static Symbol GetSymbol(String name) {
        SymbolTable table = currentSymbolTable;
        while (table != null) {
            Symbol symbol = table.GetSymbol(name);
            if (symbol != null) {
                return symbol;
            }
            table = table.GetFatherTable();
        }
        return null;
    }

    public static Symbol GetSymbolFromFather(String name) {
        SymbolTable table = currentSymbolTable.GetFatherTable();
        while (table != null) {
            Symbol symbol = table.GetSymbol(name);
            if (symbol != null) {
                return symbol;
            }
            table = table.GetFatherTable();
        }
        return null;
    }
    
    // For checking functions from root? Or anywhere?
    // SysY functions are global.
    
    public static SymbolTable GetSymbolTable() {
        return rootSymbolTable;
    }

    public static void CreateSonSymbolTable() {
        SymbolTable sonTable = new SymbolTable(currentSymbolTable.GetDepth() + 1, ++tableCounter, currentSymbolTable);
        currentSymbolTable.AddSonTable(sonTable);
        currentSymbolTable = sonTable;
    }
    
    public static void GoToFatherSymbolTable() {
        SymbolTable father = currentSymbolTable.GetFatherTable();
        if (father != null) {
            currentSymbolTable = father;
        }
    }

    public static void EnterSonScope() {
        SymbolTable son = currentSymbolTable.GetNextSonTable();
        if (son != null) {
            currentSymbolTable = son;
        } else {
            throw new RuntimeException("Scope mismatch during IR generation");
        }
    }

    public static void Reset() {
        if (rootSymbolTable != null) {
            rootSymbolTable.ResetIndex();
            currentSymbolTable = rootSymbolTable;
        }
    }

    // Context Helpers
    public static void EnterFor() { forDepth++; }
    public static void LeaveFor() { forDepth--; }
    public static boolean IsInFor() { return forDepth > 0; }
    
    public static void EnterFunc(SymbolType type) { currentFuncType = type; }
    public static void LeaveFunc() { currentFuncType = null; }
    public static SymbolType GetCurrentFuncType() { return currentFuncType; }
}
