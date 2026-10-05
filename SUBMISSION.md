# LambdaByte: Eight-bit Arithmetic in Lambda Calculus

I started with a binary encoding that gives each bit its own function argument:

```text
One   := λa b c d e f g h x. h x
Two   := λa b c d e f g h x. g x
Three := λa b c d e f g h x. g (h x)
```

A number applies exactly the functions corresponding to its set bits. Unlike
ordinary Church numerals, it does not repeat a single function once per unit.

I then built an unnecessarily elaborate arithmetic language around that encoding.
Bit extractors, Boolean gates, a full adder, an eight-bit ripple-carry adder, and a
sixteen-bit multiplier are all lambda terms. Addition, subtraction, multiplication,
unsigned carry/borrow/truncation, and signed overflow are computed by those terms.
Scala supplies the parser, the general lambda evaluator, and input/output.

The surface language uses familiar syntax:

```text
let increment = fun x -> x + 1;
print increment(41);
print 255 + 1;
print 2 - 5;
print 13 * 7;
```

The first expression produces 42. The second returns zero and reports unsigned
overflow. The third returns the byte 253, displayed also as signed -3, and reports
an unsigned borrow. The fourth produces 91. Earlier flags stay set when a result
participates in further arithmetic.

The result representation is also a lambda term: a byte and two Church Boolean
flags passed to a continuation. General lambda abstraction, application, lazy
conditionals, and a fixed-point combinator are available, so this is more than a
finite arithmetic calculator.

## Run

With Java 17 or newer:

```sh
java -jar lambdabyte.jar examples/demo.lb
java -jar lambdabyte.jar                 # interactive REPL
java -jar lambdabyte.jar --defs          # inspect every lambda definition
java -cp lambdabyte.jar LambdaByteTests --exhaustive
```

Source compilation instructions are in the README. The PDF explains the syntax,
encoding, operations, overflow detection, evaluator, and limitations.

## Intention and limits

The goal is to show how real machine arithmetic can be reconstructed from function
abstraction and application alone. Normally, `2 + 3` would be enough. Here it must
survive an entire higher-order circuit and emerge with a certificate declaring
whether overflow occurred.

Results are eight-bit words, wrapping modulo 256. Overflow detection reports flags;
it does not preserve a larger result. Division and arbitrary-precision arithmetic
are not implemented. The unrestricted lambda fragment can diverge, so the runner
has an evaluation budget. The project makes no claim to implement its parser or
interpreter inside lambda calculus itself.
