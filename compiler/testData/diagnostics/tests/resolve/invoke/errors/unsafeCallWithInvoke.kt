// !DIAGNOSTICS: -UNUSED_PARAMETER
// !WITH_NEW_INFERENCE

operator fun String.invoke(i: Int) {}

fun foo(s: String?) {
    <!UNSAFE_CALL!>s<!>(1)

    <!UNSAFE_CALL{OI}, UNSAFE_IMPLICIT_INVOKE_CALL{NI}!>(s <!USELESS_ELVIS_RIGHT_IS_NULL!>?: null<!>)<!>(1)
}
