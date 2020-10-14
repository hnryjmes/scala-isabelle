package de.unruh.isabelle.control

import java.io.{BufferedReader, FileInputStream, FileReader}
import java.nio.file.{Files, Path, Paths}

import de.unruh.isabelle.control.Isabelle.{DInt, DList, DString, Data, Setup, SetupGeneral}
import de.unruh.isabelle.control.IsabelleTest.{isabelle, setup}
import de.unruh.isabelle.mlvalue.MLValue
import de.unruh.isabelle.mlvalue.MLValueTest.await
import org.apache.commons.lang3.SystemUtils
import org.scalatest.concurrent.{Signaler, ThreadSignaler}
import org.scalatest.concurrent.TimeLimits.failAfter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Awaitable, Future}
import scala.concurrent.duration.Duration

class IsabelleTest extends AnyFunSuite {
  private def await[A](f: Awaitable[A]) : A = Await.result(f, Duration.Inf)

  test("handle compilation error") {
    assertThrows[IsabelleException] {
      isabelle.executeMLCodeNow("1+true")
    }
  }

  test("initialize Isabelle") {
    isabelle
  }

  test("executeMLCode") {
    println("Sending code")
    val exec = isabelle.executeMLCode("val _ = tracing \"Hello\"")
    println("Waiting for completion")
    await(exec)
  }

  lazy val identityId: Isabelle.ID = await(isabelle.storeValue("E_Function (fn x => x)"))
  def roundTrip(data: Data): Unit = {
    val future = isabelle.applyFunction(identityId, data)
    val returned = await(future)
    assert(returned == data)
  }

  test("roundtrip DInt") {
    roundTrip(DInt(4356))
  }

  test("roundtrip DString") {
    roundTrip(DString("hello"))
  }

  test("roundtrip DInt negative") {
    roundTrip(DInt(-235345))
  }

  test("roundtrip DList") {
    roundTrip(DList(DInt(1), DString("2")))
  }

  test("non ASCII string") {
    val result = await(isabelle.applyFunction(identityId, DString("eĥoŝanĝo ĉiuĵaŭde")))
    assert(result == DString("e?o?an?o ?iu?a?de"))
  }

  test("too long string") {
    val str = "x".repeat(70*1000*1000)
    assertThrows[IsabelleException] {
      roundTrip(DString(str))
    }
  }

  // Checks that the protocol doesn't get desynced by too long strings.
  test("too long string & continue") {
    val str = "x".repeat(70*1000*1000)
    assertThrows[IsabelleException] {
      roundTrip(DString(str))
    }
    println("Roundtrip of string finished")

    implicit val signaler: ThreadSignaler.type = ThreadSignaler
    failAfter(Span(5, Seconds)) {
      roundTrip(DInt(0))
    }
  }

  test("destroy & wait for a future") {
    implicit val isabelle: Isabelle = new Isabelle(IsabelleTest.setup)
    // Basically never finishes
    val slowComputation = isabelle.storeValue("OS.Process.sleep (Time.fromSeconds 1000000000); Match")
    isabelle.destroy()
    assertThrows[IsabelleDestroyedException] {
      await(slowComputation)
    }
  }

  test("Isabelle fails to start") {
    val setup = IsabelleTest.setup.copy(logic="NonexistingLogic")
    val isabelle = new Isabelle(setup)
    val id = isabelle.storeValue("Match")
    val exn = intercept[IsabelleDestroyedException] {
      await(id)
    }
    println(exn)
    assert(exn.message.contains("Undefined session"))
  }

  test("correct working directory") {
    val id = isabelle.storeValue("E_Function (fn _ => DString (OS.FileSys.getDir ()))")
    val DString(dir) = await(isabelle.applyFunction(id, DList()))
    println(dir)
    assert(Paths.get(dir).normalize.toAbsolutePath
      == setup.workingDirectory.normalize.toAbsolutePath)
  }
}

object IsabelleTest {
  val isabelleHome: Path = {
    val config = Paths.get(".isabelle-home") // For setting the Isabelle home in Travis CI etc.
    val path = if (Files.exists(config))
      new BufferedReader(new FileReader(config.toFile)).readLine()
    else if (SystemUtils.IS_OS_WINDOWS)
      """c:\Program Files\Isabelle2020"""
    else
      "/opt/Isabelle2020"
    Paths.get(path)
  }

  val setup: Setup = Setup(
    isabelleHome = isabelleHome,
    sessionRoots = Nil,
    userDir = None,
    workingDirectory = Path.of("src/test/isabelle"),
    build=false
  )

  implicit lazy val isabelle: Isabelle = {
    println("Starting Isabelle")
    val isa = new Isabelle(setup)
    println("Started. Initializing Term/Typ/Context")
    println("Initialized.")
    isa
  }
}

// With sockets:     1.58082ms
// With named pipes: 0.03339ms
// With sockets in Windows (VM): 0.56555ms
object Benchmark1 {
  def main(args: Array[String]): Unit = {
    val id = await(isabelle.storeValue("E_Function I"))
    println("Running warmup loop")
    for (i <- 1 to 10000) {
      await(isabelle.applyFunction(id, DInt(0)))
    }
    println("Warmup loop done")
    val count = 200000
    val time1 = System.currentTimeMillis()
    for (i <- 1 to count) {
      await(isabelle.applyFunction(id, DInt(0)))
    }
    val time2 = System.currentTimeMillis()
    val perOp = (time2-time1)*1.0/count
    println(s"Time per op: ${perOp}ms")
  }
}
