export const title = "PDF 文件切换";
export const state = (v: any) => v.generation === 0 ? "尚未选文件" : v.visible === 0 ? "读取最新文件" : v.visible === v.generation ? "当前文件就绪" : "错误：旧文件覆盖当前文件";
export const step = (action: string) => ({ Choose: "用户选择 PDF", Complete: "文件读取完成", Finish: "等待下一步" } as Record<string,string>)[action] ?? action;
