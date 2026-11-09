export const title = "PDF 拆分、取消与清理";
export const state = (v: any) => ({ idle: "待拆分", writing: "正在写入", cleanup: "正在停止并清理", cancelled: "已取消且清理完毕", cleanupError: "清理失败，有文件残留", done: "文件写入成功" } as Record<string,string>)[v.phase] ?? String(v.phase);
export const step = (action: string) => ({ Start: "开始拆分", Write: "写入一个片段", Cancel: "点击取消", Delete: "删除临时输出", DeleteFails: "存储提供方拒绝删除", ConfirmCancel: "清理确认", Complete: "全部输出完成", Finish: "停留结果页" } as Record<string,string>)[action] ?? action;
