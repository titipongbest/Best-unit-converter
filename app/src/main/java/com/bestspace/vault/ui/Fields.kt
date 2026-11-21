package com.bestspace.vault.ui

import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bestspace.vault.core.Crypto

/** ทับอักขระใน Editable ด้วยศูนย์ก่อนล้าง – กันตัวอักษรรหัสค้างในบัฟเฟอร์ของ EditText */
fun wipeEditable(e: Editable?) {
    if (e == null) return
    val n = e.length
    if (n > 0) e.replace(0, n, java.nio.CharBuffer.wrap(CharArray(n)))
    e.clear()
    e.clearSpans()
}

fun charsEqual(a: CharArray, b: CharArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}

/** บัฟเฟอร์รหัสตัวเลขแบบ CharArray ที่ล้างได้ (ไม่สร้าง String ในหน่วยความจำทุกครั้งที่กดแป้น) */
class PinBuffer(private val cap: Int = 128) {
    private var buf = CharArray(16)
    var length by mutableIntStateOf(0)
        private set

    fun append(ch: Char) {
        if (length >= cap) return
        if (length == buf.size) {
            val old = buf
            buf = old.copyOf(minOf(cap, old.size * 2))
            Crypto.wipe(old)
        }
        buf[length] = ch
        length++
    }

    fun backspace() {
        if (length > 0) {
            length--
            buf[length] = '\u0000'
        }
    }

    fun clear() {
        Crypto.wipe(buf)
        length = 0
    }

    fun takeChars(): CharArray {
        val out = buf.copyOf(length)
        clear()
        return out
    }

    fun matches(other: PinBuffer): Boolean {
        if (length != other.length) return false
        var diff = 0
        for (i in 0 until length) diff = diff or (buf[i].code xor other.buf[i].code)
        return diff == 0
    }

    fun revealString(): String = String(buf, 0, length)
}

/** ตัวคุมช่องรหัสผ่านที่อ่านค่าเป็น CharArray โดยตรงเมื่อกดส่ง (ไม่สร้าง String ใน Compose ระหว่างพิมพ์) */
class SecretController {
    internal var editText: SecureEditText? = null
    var length by mutableIntStateOf(0)
        internal set

    fun copyChars(): CharArray {
        val ed = editText?.text ?: return CharArray(0)
        val n = ed.length
        return CharArray(n).also { if (n > 0) android.text.TextUtils.getChars(ed, 0, n, it, 0) }
    }

    fun takeChars(): CharArray {
        val out = copyChars()
        clear()
        return out
    }

    fun clear() {
        wipeEditable(editText?.text)
        length = 0
    }
}

/**
 * EditText ที่ปลอดภัยขึ้น:
 * • คัดลอก/ตัด → ติดป้าย "ข้อมูลอ่อนไหว" ให้คลิปบอร์ด: คีย์บอร์ด (Gboard/Samsung) ไม่เก็บลงประวัติคลิปบอร์ด
 *   และ Android 13+ ไม่แสดงตัวอย่างข้อความบนจอ
 * • ไม่บันทึกข้อความลง saved-state ของระบบ (ตอนแอปถูกปิดเบื้องหลัง)
 */
class SecureEditText(ctx: android.content.Context) : EditText(ctx) {
    init {
        isSaveEnabled = false
        freezesText = false
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.copy || id == android.R.id.cut) {
            val t = text ?: return super.onTextContextMenuItem(id)
            val a = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
            val b = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
            if (b > a) {
                val clip = android.content.ClipData.newPlainText("", t.subSequence(a, b))
                val extras = android.os.PersistableBundle()
                // ClipDescription.EXTRA_IS_SENSITIVE (Android 13+) – เครื่องรุ่นเก่าใช้คีย์เดียวกัน (คีย์บอร์ดบางตัวรองรับ)
                extras.putBoolean("android.content.extra.IS_SENSITIVE", true)
                clip.description.extras = extras
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                    ?.setPrimaryClip(clip)
                if (id == android.R.id.cut && keyListener != null) t.delete(a, b)
                else setSelection(b)
                return true
            }
        }
        return super.onTextContextMenuItem(id)
    }
}

/**
 * ช่องพิมพ์รหัสผ่านแบบ "คีย์บอร์ดไม่จำ" และไม่ส่ง String กลับ Compose ระหว่างพิมพ์
 */
@Composable
fun SecretField(
    controller: SecretController,
    label: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    onChange: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    textColor: androidx.compose.ui.graphics.Color = Palette.Fg,
    labelColor: androidx.compose.ui.graphics.Color = Palette.Muted,
) {
    var show by remember { mutableStateOf(false) }
    val changed by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    DisposableEffect(controller) {
        onDispose {
            controller.clear()
            controller.editText = null
        }
    }
    Column(modifier) {
        Text(label, fontSize = 13.sp, color = labelColor)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { ctx ->
                    SecureEditText(ctx).apply {
                        controller.editText = this
                        setTextColor(textColor.toArgb())
                        setHintTextColor(labelColor.toArgb())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        isSingleLine = true
                        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE or
                            EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                        inputType = typeFor(true, false)
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun afterTextChanged(s: Editable?) {
                                controller.length = s?.length ?: 0
                                changed?.invoke()
                            }
                        })
                        setOnEditorActionListener { _, action, _ ->
                            if (action == EditorInfo.IME_ACTION_DONE && done != null) { done?.invoke(); true } else false
                        }
                        if (autoFocus) post {
                            requestFocus()
                            (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                                ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                },
                update = { et ->
                    controller.editText = et
                    val t = typeFor(true, show)
                    if (et.inputType != t) {
                        val sel = et.selectionEnd
                        et.inputType = t
                        et.typeface = Typeface.DEFAULT
                        et.setSelection(sel.coerceIn(0, et.text.length))
                    }
                },
            )
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "แสดงรหัส", tint = Palette.Muted)
            }
        }
    }
}

/**
 * ช่องพิมพ์แบบ "คีย์บอร์ดไม่จำ" (IME_FLAG_NO_PERSONALIZED_LEARNING = โหมดไม่ระบุตัวตนของ Gboard ฯลฯ)
 * – ชื่อไฟล์/รหัสผ่าน/ข้อความในตู้ ไม่ไปค้างในพจนานุกรม/คำแนะนำของคีย์บอร์ด
 */
@Composable
fun IncognitoField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    autoFocus: Boolean = false,
    onDone: (() -> Unit)? = null,
    textColor: androidx.compose.ui.graphics.Color = Palette.Fg,
    labelColor: androidx.compose.ui.graphics.Color = Palette.Muted,
) {
    var show by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    var ref by remember { mutableStateOf<SecureEditText?>(null) }
    if (password) DisposableEffect(Unit) {
        onDispose { wipeEditable(ref?.text); ref = null }
    }
    Column(modifier) {
        Text(label, fontSize = 13.sp, color = labelColor)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { ctx ->
                    SecureEditText(ctx).apply {
                        ref = this
                        setTextColor(textColor.toArgb())
                        setHintTextColor(labelColor.toArgb())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        isSingleLine = true
                        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE or
                            EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                        inputType = typeFor(password, false)
                        setText(value)
                        setSelection(text.length)
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun afterTextChanged(s: Editable?) { change(s?.toString() ?: "") }
                        })
                        setOnEditorActionListener { _, action, _ ->
                            if (action == EditorInfo.IME_ACTION_DONE && done != null) { done?.invoke(); true } else false
                        }
                        if (autoFocus) postDelayed({
                            if (isAttachedToWindow) {
                                requestFocus()
                                (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                                    ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }, 60)
                    }
                },
                update = { et ->
                    ref = et
                    if (et.text.toString() != value) { et.setText(value); et.setSelection(et.text.length) }
                    val t = typeFor(password, show)
                    if (et.inputType != t) {
                        val sel = et.selectionEnd
                        et.inputType = t
                        et.typeface = Typeface.DEFAULT
                        et.setSelection(sel.coerceIn(0, et.text.length))
                    }
                },
            )
            if (password) IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "แสดงรหัส", tint = Palette.Muted)
            }
        }
    }
}

private fun typeFor(password: Boolean, show: Boolean): Int = when {
    password && show -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
}

/** ช่องแก้ไขข้อความยาว (ไฟล์ .txt ในตู้) – คีย์บอร์ดไม่จำ, ไม่ส่งข้อความกลับ Compose ทุกครั้งที่พิมพ์ (เร็วกับไฟล์ใหญ่) */
@Composable
fun TextEditorView(
    initial: String,
    editable: Boolean,
    autoFocus: Boolean,
    onCreated: (EditText) -> Unit,
    onDirty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dirty by rememberUpdatedState(onDirty)
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            val et = SecureEditText(ctx).apply {
                setTextColor(Palette.FgInt)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.TOP or Gravity.START
                background = null
                isVerticalScrollBarEnabled = false
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                isSingleLine = false
                setHorizontallyScrolling(false)
                setText(initial)
                if (!editable) { keyListener = null; setTextIsSelectable(true) }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { dirty() }
                })
                onCreated(this)
                if (autoFocus && editable) post {
                    requestFocus()
                    (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            ScrollView(ctx).apply {
                isFillViewport = true
                isSaveEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(et, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        },
    )
}
