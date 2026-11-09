export const title = "PDF 章节选择与规划";
export const state = (v: any) => v.planned === true ? "已生成章节计划" : (Array.isArray(v.selected) && v.selected.length) ? "已选章节" : "等待选择章节";
export const step = (action: string) => ({ Select: "勾选章节", Build: "检查归属并生成计划", Finish: "计划就绪" } as Record<string,string>)[action] ?? action;
