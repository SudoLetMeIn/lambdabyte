/** Dependency-free tests. Host arithmetic is the independent test oracle only. */
object LambdaByteTests {
  import LambdaByte._
  private var checks = 0L
  private def check(condition: Boolean, description: String): Unit = {
    checks += 1
    if (!condition) throw new AssertionError(description)
  }
  private def signed(byte: Int): Int = if (byte < 128) byte else byte - 256
  private def packet(session: Session, expression: String): Packet =
    decodePacket(session.value(expression)).getOrElse(throw new AssertionError("not a packet: " + expression))
  private def rejected(body: => Unit, description: String): Unit = {
    var failed = false
    try body catch { case _: LanguageError => failed = true }
    check(failed, description)
  }
  def main(arguments: Array[String]): Unit = {
    val exhaustive = arguments.contains("--exhaustive")
    val session = new Session()
    val started = System.nanoTime()
    val words = (0 until 256).map(Core.word(_)).toVector
    for (number <- 0 until 256) {
      check(decodeWord(session.evaluate(words(number))).contains(number), "word " + number)
      for (bit <- 0 until 8) {
        val actual = decodeBoolean(session.evaluate(call("B8_" + bit, words(number))))
        check(actual.contains((number & (1 << bit)) != 0), "bit " + bit + " of " + number)
      }
    }
    println("All 256 numerals and 2,048 bit probes passed.")
    val inputs = if (exhaustive) (0 until 256).toVector else
      Vector(0, 1, 2, 3, 7, 15, 16, 31, 63, 64, 100, 127, 128, 129, 200, 254, 255)
    for (operation <- Vector("ADD", "SUB", "MUL")) {
      var pairs = 0
      for (m <- inputs; n <- inputs) {
        val unsigned = operation match {
          case "ADD" => m + n
          case "SUB" => m - n
          case "MUL" => m * n
        }
        val signedResult = operation match {
          case "ADD" => signed(m) + signed(n)
          case "SUB" => signed(m) - signed(n)
          case "MUL" => signed(m) * signed(n)
        }
        val expected = Packet(unsigned & 255, unsigned < 0 || unsigned > 255,
          signedResult < -128 || signedResult > 127)
        val result = decodePacket(session.evaluate(call(operation + "_RAW", words(m), words(n))))
        check(result.contains(expected), operation + "(" + m + "," + n + "): " + result + " expected " + expected)
        pairs += 1
      }
      println(operation + ": " + pairs + " pairs, byte and both flags passed.")
    }

    check(packet(session, "2 + 3 * 4").byte == 14, "multiplication precedence")
    check(packet(session, "10 - 3 - 2").byte == 5, "subtraction associates left")
    check(packet(session, "0b00001101 * 0b00000111").byte == 91, "binary literals")
    check(packet(session, "-1 * 1") == Packet(255, false, false), "signed multiplication is not unsigned high-byte checking")
    check(packet(session, "-128 * -1") == Packet(128, true, true), "signed multiplication boundary")
    check(packet(session, "-(3 + 4)") == Packet(249, true, false), "unary negation")
    check(packet(session, "(255 + 1) + 0").unsignedFault, "unsigned flag is sticky")
    check(packet(session, "(127 + 1) - 1").signedOverflow, "signed flag is sticky")
    check(packet(session, "if true then 7 else (255 + 1)") == Packet(7, false, false), "unchosen branch is lazy")
    check(packet(session, "(fun ignored -> 7)((fun x -> x(x))(fun x -> x(x)))").byte == 7, "ignored divergent argument")
    check(decodeBoolean(session.value("eq(13)(13)")).contains(true), "equality true")
    check(decodeBoolean(session.value("eq(13)(7)")).contains(false), "equality false")
    check(decodeBoolean(session.value("has_fault(255 + 1)")).contains(true), "flag predicate")
    check(decodeBoolean(session.value("unsigned_fault(127 + 1)")).contains(false), "carry is distinct from signed overflow")
    check(decodeBoolean(session.value("signed_overflow(127 + 1)")).contains(true), "signed flag predicate")
    session.execute("let y = 42; let f = fun x -> y; let y = 3;")
    check(packet(session, "f(0)").byte == 42, "lexical closure capture")
    check(packet(session, "(fun x -> (fun x -> x)(2))(1)").byte == 2, "variable shadowing")
    session.execute("let ADD_LIFT = fun x -> 0;")
    check(packet(session, "1 + 2").byte == 3, "user names cannot capture internal operators")
    session.execute("let fact = fix(fun self -> fun n -> if is_zero(n) then 1 else n * self(n - 1));")
    check(packet(session, "fact(5)") == Packet(120, false, false), "fixed point recursion")
    check(packet(session, "fact(6)") == Packet(208, true, true), "recursive flags propagate")
    rejected({ session.value("256"); () }, "oversized literal rejected")
    rejected({ session.value("-129"); () }, "out-of-range signed literal rejected")
    rejected({ session.value("0b102"); () }, "invalid binary literal rejected")
    rejected({ session.value("missing"); () }, "unknown name rejected")
    rejected({ session.value("fun x x"); () }, "syntax error rejected")
    rejected({ new Session(1000).value("(fun x -> x(x))(fun x -> x(x))"); () }, "divergence bounded")
    check(session.execute("# comment\nprint 1; // comment\nprint 2;").size == 2, "both comment forms")
    check(decodeBoolean(session.value("fun t -> fun f -> t")).contains(true), "ordinary lambda calculus is available")
    check(packet(session, "let x = 3 in x * 4").byte == 12, "local let")
    check(packet(session, "let y = 8 in let y = y + 1 in y").byte == 9, "local shadowing")
    check(packet(session, "if true then if false then 1 else 2 else 3").byte == 2, "nested conditionals")
    check(packet(session, "(fun x y -> x + y)(4)(5)").byte == 9, "multiple lambda parameters")
    session.execute("let add x y = x + y;")
    check(packet(session, "add(7)(8)").byte == 15, "function binding shorthand")
    session.execute("let rec factorial n = if is_zero(n) then 1 else n * factorial(n - 1);")
    check(packet(session, "factorial(5)") == Packet(120, false, false), "let rec desugars to FIX")
    check(packet(session, "let rec f n = if is_zero(n) then 1 else n * f(n - 1) in f(4)").byte == 24, "local let rec")
    rejected({ session.value("1 aura 2"); () }, "old arithmetic syntax rejected")
    rejected({ session.execute("vibe x = 1;"); () }, "old binding syntax rejected")
    rejected({ session.value("if true ? 1 : 2"); () }, "old conditional syntax rejected")
    val seconds = (System.nanoTime() - started) / 1000000000.0
    println("PASS: " + checks + " checks in " + ("%.2f".format(seconds)) + " seconds.")
  }
}
