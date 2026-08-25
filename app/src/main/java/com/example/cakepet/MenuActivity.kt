package com.example.cakepet

/**
 * 菜单动作常量集合（原 MenuActivity）。
 * 长按菜单已改为 PetService 之上的 TYPE_APPLICATION_OVERLAY 浮窗（见 PetMenu），
 * 不再使用 Activity，此处仅保留 action 字符串常量供 PetService.performAction / PetMenu 复用。
 */
object MenuActivity {
    const val ACTION_PAT = "com.example.cakepet.action.PAT"
    const val ACTION_JUMP = "com.example.cakepet.action.JUMP"
    const val ACTION_ROLL = "com.example.cakepet.action.ROLL"
    const val ACTION_PULL_FISH = "com.example.cakepet.action.PULL_FISH"
    const val ACTION_WHITE = "com.example.cakepet.action.WHITE"
    const val ACTION_PUFFED = "com.example.cakepet.action.PUFFED"
    const val ACTION_SIT_CLAM = "com.example.cakepet.action.SIT_CLAM"
    const val ACTION_SHAKE_HEAD = "com.example.cakepet.action.SHAKE_HEAD"
    const val ACTION_WALK = "com.example.cakepet.action.WALK"
    const val ACTION_WRIGGLE = "com.example.cakepet.action.WRIGGLE"
    const val ACTION_LIE = "com.example.cakepet.action.LIE"
    const val ACTION_PROBE_HEAD = "com.example.cakepet.action.PROBE_HEAD"
    const val ACTION_HIDE = "com.example.cakepet.action.HIDE"
    const val ACTION_RECALL = "com.example.cakepet.action.RECALL"
    const val ACTION_EXIT = "com.example.cakepet.action.EXIT"
    const val ACTION_SETTINGS = "com.example.cakepet.action.SETTINGS"
}
