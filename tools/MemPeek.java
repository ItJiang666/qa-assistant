import java.sql.*;

/** 只读查看长期记忆表：是否真有"删了还在"的痕迹。 */
public class MemPeek {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:file:C:/Users/Jiang/.qa-assistant/data/qa;MODE=MySQL;AUTO_SERVER=TRUE;IFEXISTS=TRUE";
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement()) {

            System.out.println("=== qa_memory 按状态统计 ===");
            try (ResultSet rs = st.executeQuery(
                    "SELECT status, COUNT(*) FROM qa_memory GROUP BY status")) {
                while (rs.next()) System.out.printf("  status=%-8s -> %d 条%n", rs.getString(1), rs.getInt(2));
            }

            System.out.println("\n=== qa_memory 全部行（含 DELETED，看有没有重复内容）===");
            try (ResultSet rs = st.executeQuery(
                    "SELECT id, user_id, status, mem_key, LEFT(content, 46) AS c, importance, hit_count, "
                  + "created_at, updated_at FROM qa_memory ORDER BY id")) {
                while (rs.next()) {
                    System.out.printf("id=%-4d uid=%-14s %-8s key=%-22s imp=%d hit=%d%n",
                            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(6), rs.getInt(7));
                    System.out.printf("        content=%s%n", rs.getString(5));
                    System.out.printf("        created=%s  updated=%s%n", rs.getTimestamp(8), rs.getTimestamp(9));
                }
            }

            System.out.println("\n=== 会话表 ===");
            try (ResultSet rs = st.executeQuery(
                    "SELECT conversation_id, user_id, title, updated_at FROM qa_conversation ORDER BY updated_at DESC")) {
                while (rs.next()) {
                    System.out.printf("  conv=%s uid=%s title=%s updated=%s%n",
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4));
                }
            }

            System.out.println("\n=== qa_message 条数按会话 ===");
            try (ResultSet rs = st.executeQuery(
                    "SELECT conversation_id, COUNT(*) FROM qa_message GROUP BY conversation_id ORDER BY 2 DESC")) {
                while (rs.next()) System.out.printf("  conv=%s -> %d 条%n", rs.getString(1), rs.getInt(2));
            }
        }
    }
}
