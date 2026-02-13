package de.sandstorm.datagriphelpers;

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class OpenerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        OpenerCommandLine.process("mariadb", "jdbc:mariadb://localhost:3306", "root", "password", "title-here2")
    }
}
