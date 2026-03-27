package midend.symbol;

public enum SymbolType {
    CHAR("Char"),
    INT("Int"),
    STATIC_INT("StaticInt"),

    CHAR_ARRAY("CharArray"),
    INT_ARRAY("IntArray"),
    STATIC_INT_ARRAY("StaticIntArray"),

    CONST_CHAR("ConstChar"),
    CONST_INT("ConstInt"),
    
    CONST_CHAR_ARRAY("ConstCharArray"),
    CONST_INT_ARRAY("ConstIntArray"), // Need to handle dimensions

    VOID_FUNC("VoidFunc"),
    CHAR_FUNC("CharFunc"),
    INT_FUNC("IntFunc"),

    ERROR("Error");

    private final String typeName;

    SymbolType(String typeName) {
        this.typeName = typeName;
    }

    @Override
    public String toString() {
        return this.typeName;
    }

    // Helper methods to determine type from BType string and context
    public static SymbolType getType(String bType, boolean isConst, boolean isStatic, int dim) {
        if (bType.equals("int")) {
            if (dim > 0) {
                 return isConst ? CONST_INT_ARRAY : INT_ARRAY; 
            }
            if (isConst) return CONST_INT;
            if (isStatic) return STATIC_INT;
            return INT;
        }
        if (bType.equals("void")) return VOID_FUNC;
        return ERROR;
    }

    public static SymbolType GetVarType(String typeString, int dimension, boolean isStatic) {
        if (dimension > 0) {
            return isStatic ? STATIC_INT_ARRAY : INT_ARRAY;
        }
        if (typeString.equals("int")) {
            return isStatic ? STATIC_INT : INT;
        }
        return ERROR;
    }

    public static SymbolType GetConstType(String typeString, int dimension) {
        if (dimension > 0) return CONST_INT_ARRAY;
        if (typeString.equals("int")) return CONST_INT;
        return ERROR;
    }

    public static SymbolType GetFuncType(String typeString) {
        if (typeString.equals("int")) return INT_FUNC;
        if (typeString.equals("void")) return VOID_FUNC;
        return ERROR;
    }
}
