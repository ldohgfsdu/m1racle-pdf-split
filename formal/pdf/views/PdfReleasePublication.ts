export const title = "经过远端校验的正式发布";
export const state = (v: any) => ({ new: "准备构建", built: "已签名构建", draft: "草稿发布", uploaded: "附件已上传", verified: "已回读验证", public: "公开 Release", failed: "发布失败（不公开）" } as Record<string,string>)[v.phase] ?? String(v.phase);
export const step = (action: string) => ({ Build: "构建签名 APK", CreateDraft: "创建草稿", Upload: "上传 APK 与 SHA-256", Verify: "下载并校验附件", Publish: "校验后公开", Fail: "发布中止", Finish: "发布流程结束" } as Record<string,string>)[action] ?? action;
