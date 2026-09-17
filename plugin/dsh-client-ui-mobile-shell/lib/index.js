// host 侧入口：本插件只在浏览器里做事，host 半边是空的。
// dsh 的客户端插件机制要求 host 半边存在且可被 loader 挂载。
/** Host 侧无行为。 */
export function apply() {}
