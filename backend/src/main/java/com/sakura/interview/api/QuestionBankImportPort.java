package com.sakura.interview.api;

/**
 * 题库导入端口：管理端上传的题库 Markdown 经此入库，幂等（题号已存在跳过）。
 * 生产实现走 rag（解析 + Milvus），单测用内存实现验证门禁与口径。
 */
public interface QuestionBankImportPort {

    /**
     * 解析并导入题库文本（与 question-bank 目录的文件同格式）。
     * @return 解析题数与本次实际入库数（差值为跳过的已存在题号）
     * @throws IllegalArgumentException 内容格式不合法（字段缺失、题号重复、无题目）
     */
    ImportResult importText(String content);

    record ImportResult(int parsed, int imported) {
        public int skipped() {
            return parsed - imported;
        }
    }
}
