/**
 * 各平台绑定时填什么：label 输入框的名称，example 占位示例，help 填写说明，
 * short 按钮上的短称（「下一步：填 Codeforces 用户名」），hint 选平台时的一句特点（总览的常用平台也用），
 * numeric 只能填数字，nickname 只能按昵称查找（码蹄集），查到同名账号再选本人。
 */
export interface BindingGuide {
  label: string
  example: string
  help: string
  short: string
  hint?: string
  numeric?: boolean
  nickname?: boolean
}
export const bindingGuides: Record<string, BindingGuide> = {
  CODEFORCES: { label: '用户名（Handle）', short: 'Codeforces 用户名', hint: '比赛最多', example: '例如 example', help: '填写 Codeforces 个人主页 /profile/ 后的用户名，不是数字 UID，也不是姓名。' },
  ATCODER: { label: '用户名（User ID）', short: 'AtCoder 用户名', hint: '每周新手赛', example: '例如 example', help: '填写 AtCoder 个人主页 /users/ 后的用户名，不是显示昵称。' },
  NOWCODER: { label: '数字 UID（不是昵称）', short: '牛客 UID', hint: '国内比赛多', example: '例如 123456789', help: '仅填写牛客数字 UID。打开个人主页，复制 /profile/ 后的数字；不要填写昵称、手机号或邮箱。', numeric: true },
  MATIJI: { label: '昵称', short: '码蹄集昵称', hint: '按昵称查找', example: '例如：示例同学', help: '填写完整昵称，先查站内共享的昵称索引，没找到再到排行榜里实时查找。同名账号请核对学校、排名和通过数；选中本人后把验证串写进码蹄集个性签名完成归属验证。', nickname: true },
  LEETCODE_CN: { label: '个人主页标识（userSlug）', short: '力扣主页标识', hint: '中文界面', example: '例如 example-user', help: '填写力扣中国站个人主页 /u/ 后的标识，不是中文昵称、邮箱或手机号。' },
  LUOGU: { label: '洛谷用户名', short: '洛谷用户名', hint: '中文题目多', example: '例如 example', help: '当前绑定适配器按用户名搜索，请填写完整洛谷用户名；UID 会在解析成功后展示。练习页文件导出是另一项功能。' },
  HDU: { label: 'HDU 登录用户名', short: '杭电用户名', example: '例如 example', help: '填写杭电 OJ 登录用户名，即个人统计页 user= 后的值；不是显示昵称。' },
  QOJ: { label: 'QOJ 用户名', short: 'QOJ 用户名', example: '例如 example', help: '填写个人主页 /user/profile/ 后的用户名，再连接浏览器插件同步。不需要填写 Cookie 或密码；未验证归属的记录仅用于个人统计。' },
}
