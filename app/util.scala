package app

extension [E, A](either: Either[E, A])
  def tap(f: A => Unit): Either[E, A] =
    either match
      case Left(e) =>
        either
      case Right(a) =>
        f(a)
        either
  def tapBoth(f: E => Unit, g: A => Unit): Either[E, A] =
    either match
      case Left(e) =>
        f(e)
        either
      case Right(a) =>
        g(a)
        either

  def orThrow(using E <:< Throwable): A =
    either match
      case Left(e)  => throw e
      case Right(a) => a
