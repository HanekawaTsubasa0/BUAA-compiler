package midend.visit;

import ASTNode.BlockItemNode;
import ASTNode.BlockNode;

public class VisitorBlock {
    public static void VisitBlock(BlockNode node) {
        for (BlockItemNode item : node.getBlockItemNodes()) {
            if (item.getDeclNode() != null) {
                VisitorDecl.VisitDecl(item.getDeclNode());
            } else {
                VisitorStmt.VisitStmt(item.getStmtNode());
            }
        }
    }
}
