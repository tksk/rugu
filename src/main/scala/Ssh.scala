package com.novus.rugu

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import scala.util.control.Exception.allCatch
import java.io.{File, InputStream}

trait Executor {
  def apply[A](command: Command[_, A])(f: InputStream => A): Either[Throwable, (Option[Int], A, String)]
  def upload(localFile: String, remotePath: String): Either[Throwable, Unit]
  def download(remotePath: String, localFile: String): Either[Throwable, Unit]
}

case class Host(name: String, port: Int = 22)

object Ssh {
  def apply(template: Template): SshSession =
    apply(template.host, template.auth, template.knownHostsFile)
  
  def apply(host: Host, auth: Authentication, knownHostsFile: Option[String] = None): SshSession = {
    val hostVerifier = knownHostsFile.map(f => new OpenSSHKnownHosts(new File(f)))
    
    val executor = new Executor {
      private def session[A](op: SSHClient => A): Either[Throwable, A] = {
        val ssh = new SSHClient()
        hostVerifier.foreach(ssh.addHostKeyVerifier(_))
        allCatch.andFinally(ssh.disconnect()).either {
          /* Connect and authenticate. */
          ssh.connect(host.name, host.port)
          auth match {
            case PublicKey(u, k, pass) =>
              val kp = pass.map(ssh.loadKeys(k, _)).getOrElse(ssh.loadKeys(k)) //FIXME don't load keys every time
              ssh.authPublickey(u, kp)
            case UsernameAndPassword(u, p) =>
              ssh.authPassword(u, p)
          }
          op(ssh)
        }
      }
      
      def upload(localFile: String, remotePath: String) =
        session(_.newSCPFileTransfer().upload(localFile, remotePath))
      
      def download(remotePath: String, localFile: String) =
        session(_.newSCPFileTransfer().download(remotePath, localFile))
      
      def apply[A](command: Command[_, A])(f: InputStream => A): Either[Throwable, (Option[Int], A, String)] = {
        session { ssh =>
          IO(ssh.startSession()) { s =>
            /* Exec command with input if any, collect and transform output,
             * error output, and exit status if given.
             */
            val c = s.exec(command.command)
            command.input.foreach { in =>
              IO(c.getOutputStream()) { _.write(in.getBytes()) }
            }
            val a = f(c.getInputStream())
            val err = scala.io.Source.fromInputStream(c.getErrorStream()).mkString
            c.join(5, java.util.concurrent.TimeUnit.SECONDS) //TODO
            (Option(c.getExitStatus).map(_.intValue), a, err)
          }
        }.fold(Left(_), identity)
      }
    }
    
    new SshSession(executor)
  }
}

class SshSession(executor: Executor) {
  def apply[I : StreamProcessor, O](c: Command[I, O]): Either[Throwable, O] =
    exec(c)(identity).fold(
      Left(_), {
        case (Some(0), o, os) => Right(o)
        case (i, o, os) => Left(new RuntimeException("%d: %s".format(i.getOrElse(-1), os)))
      })
  
  def exec[I, O, OO](c: Command[I, O])(f: ((Option[Int], O, String)) => OO)(implicit sp: StreamProcessor[I]): Either[Throwable, OO] =
    executor(c)(sp andThen c).fold(Left(_), r => Right(f(r)))
  def upload(localFile: String, remotePath: String) =
    executor.upload(localFile, remotePath)
}

object IO {
  type Resource = { def close(): Unit }
  def apply[A, R <: Resource](r: R)(op: R => A): Either[Throwable, A] =
    allCatch.andFinally(r.close()).either(op(r))
}
