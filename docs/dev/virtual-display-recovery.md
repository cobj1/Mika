# 虚拟屏异常退出恢复

借鉴 [Agent-VScreen 的屏由特权进程持有、替换 Surface 接回设计](https://github.com/kilolonion/agent-vscreen/blob/7e2e01491797848a82bb8bfe611842fb87fb9d10/app/src/main/java/com/kilon/agentvscreen/core/VScreenDisplayService.kt)，在 Mika 的现有 helper、任务隔离和清理机制内独立实现。

## 行为边界

- 每次使用手机的任务通过 `RecoverableDisplay.create` 取得会话凭证。凭证是随机 UUID，同步存入应用私有偏好；模型和工具参数拿不到凭证。
- 同一会话的并行任务在同进程内领取不同凭证，helper 也拒绝接走仍在使用的屏；不同会话不按 displayId 猜测归属。
- Mika 进程异常死亡、helper 仍存活时，helper 立即取消持续手指、还原剪贴板和静音，停止为屏续亮，将 Surface 置空。原屏接回期限为 **120 秒**；期限判断包含深睡时间，实际资源释放在系统恢复调度后执行，不为清理唤醒手机。
- 此后用户在同一会话重新发起任务，且任务实际需要手机能力时，才用凭证接回该屏，并接上新 ImageReader 的 Surface。旧任务仍按原有恢复规则标记中断；不会自动执行它未完成的工具调用。
- 屏大小或 DPI 改变、已超过期限、helper 已死亡或被换新时，创建新屏。原生 Android 屏状态不能跨 helper 进程死亡保存。
- 普通任务结束/用户停止仍按 Mika 原有规则处理应用并释放屏，同时清除会话凭证。强行停止应用时立即释放全部屏。若系统无法读出强停标志，则最多保留到 120 秒超时。
- 自检等未登记会话凭证的临时屏仍在进程死亡时立即销毁。
- 普通 `SharedShell` 空闲解绑释放活动屏及系统改动，但不提前销毁已停放屏；停放期限不会因再次绑定、解绑或重复进程死亡而延长。
- 停放期间禁止向该屏启动应用或注入输入；系统后台行为仍受 Android 和目标应用自身生命周期影响。保留屏不等于保证第三方应用进程存活。

## 验证

`DisplayRecoveryLedgerTest` 验证活动凭证拒绝、只接停放屏、超时边界、不延长孤立屏期限、旧计时器不误删接回屏、释放清归属。

`DisplaySessionTokensTest` 验证重启时只恢复同会话凭证、并行运行隔离、正常结束清理。

`DisplayRecoveryDeviceTest` 验证 binder 入口的活动屏抢占拒绝及 displayId 不可作凭证。该测试需要已运行的 helper；设备测试编译通过不代表真机已验收。

需在真机完成的流程：

1. 开始一个手机任务，打开测试页面，记录 helper PID、displayId 和页面内容。
2. 在持续触摸期间让 Mika 应用进程异常退出（不要强停 helper，也不要通过普通停止按钮）。确认没有持续按压遗留、静音与剪贴板恢复、屏 Surface 已移除。
3. 在 120 秒内重新打开 Mika，在同一会话发起新任务。确认 helper PID 没变、原 displayId 和页面被接回，旧任务没有自动继续，新的截图来自新 Surface。
4. 在不同会话发起任务，确认不能接回该屏；等待超过 120 秒，确认遗留屏销毁。
5. 分别测试正常任务结束、用户停止、强行停止 Mika、终止 helper、旋转/修改显示尺寸。只有符合上述边界的异常退出路径保留原屏。

目前未连接 Android 设备，不宣称上述真机恢复流程已经通过。
