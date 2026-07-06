/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package tgx.gradle.task

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.Charset
import java.util.*
import javax.inject.Inject

abstract class GitVersionValueSource : ValueSource<GitVersionValueSource.Details, GitVersionValueSource.Params> {
  interface Params : ValueSourceParameters {
    val module: DirectoryProperty
  }

  data class Details(
    val commitHashShort: String,
    val commitHashLong: String,
    val commitDate: Long,
    val remoteUrl: String
  ) {
    constructor(output: String) : this(output.trim().split('\n', limit = 5))
    constructor(git: List<String>) : this(
      git[0],
      git[1],
      git[2].toLong(),
      when {
        git[3].startsWith("git@") -> {
          val index = git[3].indexOf(':', 4)
          val domain = git[3].substring(4, index)
          val endIndex = if (git[3].endsWith(".git")) {
            git[3].length - 4
          } else {
            git[3].length
          }
          val query = git[3].substring(index + 1, endIndex)
          "https://${domain}/${query}"
        }
        git[3].endsWith(".git") -> {
          git[3].substring(0, git[3].length - 4)
        }
        else -> {
          git[3]
        }
      }
    )

    val commitUrl: String
      get() = String.format(Locale.ENGLISH, $$"%1$s/tree/%3$s", remoteUrl, commitHashShort, commitHashLong)
  }

  @get:Inject
  abstract val execOperations: ExecOperations

  override fun obtain(): Details {
    val submodule = parameters.module.get().asFile
    val path = if (submodule.exists() && submodule.isDirectory) {
      submodule.absolutePath
    } else {
      ""
    }
    val data = buildString {
      appendLine(runGitCommand(path, "rev-parse", "--short", "HEAD") ?: return fallbackDetails())
      appendLine(runGitCommand(path, "rev-parse", "HEAD") ?: return fallbackDetails())
      appendLine(runGitCommand(path, "show", "-s", "--format=%ct") ?: return fallbackDetails())
      appendLine(runGitCommand(path, "config", "--get", "remote.origin.url") ?: "https://github.com/TGX-Android/Telegram-X")
      append(runGitCommand(path, "log", "-1", "--pretty=format:%an") ?: "Local Build")
    }
    val details = Details(data)
    if (URI.create(details.remoteUrl).host != "github.com") {
      return fallbackDetails()
    }
    return details
  }

  private fun runGitCommand (path: String, vararg args: String): String? {
    val output = ByteArrayOutputStream()
    return try {
      execOperations.exec {
        if (path.isNotEmpty()) {
          commandLine("git", "-C", path, *args)
        } else {
          commandLine("git", *args)
        }
        standardOutput = output
      }
      String(output.toByteArray(), Charset.defaultCharset()).trim().takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
      null
    }
  }

  private fun fallbackDetails (): Details {
    return Details(
      "0000000",
      "0000000000000000000000000000000000000000",
      System.currentTimeMillis() / 1000L,
      "https://github.com/TGX-Android/Telegram-X"
    )
  }
}
