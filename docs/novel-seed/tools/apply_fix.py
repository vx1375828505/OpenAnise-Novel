# -*- coding: utf-8 -*-
"""精确修复 OpenAnise-Novel 的 9 个 Kotlin 单测编译错误。
每处替换带 count==1 断言,防止误改。仅改测试源码,不碰主代码/签名。"""
import io, sys

B = r'C:\1111\openanise-fix\app\src\test\java\com\openminis\app'

def edit(path, pairs):
    p = B + '\\' + path
    with io.open(p, 'r', encoding='utf-8') as f:
        s = f.read()
    for old, new, label in pairs:
        n = s.count(old)
        if n != 1:
            print('  [FAIL] %s : 预期1处,实际%d处 -> %r' % (label, n, old[:60]))
            sys.exit(1)
        s = s.replace(old, new, 1)
        print('  [OK] %s' % label)
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)

# ── 1) CtxScenarios.kt : internal 可见性连锁(4 个 public 声明暴露 internal 的 RequestContextBuilder) ──
edit(r'ctx\CtxScenarios.kt', [
    ('class CtxPhase(', 'internal class CtxPhase(',
     'CtxPhase -> internal (第17行根因)'),
    ('class CtxRun(val scenario:', 'internal class CtxRun(val scenario:',
     'CtxRun -> internal (暴露 CtxPhase)'),
    ('class CtxDesired(val text:', 'internal class CtxDesired(val text:',
     'CtxDesired -> internal (暴露 CtxRun)'),
    ('class CtxScenario(', 'internal class CtxScenario(',
     'CtxScenario -> internal (暴露 CtxRun/CtxDesired)'),
    ('object CtxScenarios {', 'internal object CtxScenarios {',
     'CtxScenarios -> internal (暴露 CtxPhase 参数)'),
])

# ── 2) CtxSession.kt : input()/request() 返回 internal 类型 ──
edit(r'ctx\CtxSession.kt', [
    ('    fun input(): RequestContextBuilder.Input =',
     '    internal fun input(): RequestContextBuilder.Input =',
     'input() -> internal (第304行)'),
    ('    fun request(): RequestContextBuilder.RequestContext =',
     '    internal fun request(): RequestContextBuilder.RequestContext =',
     'request() -> internal (第322行)'),
])

# ── 3) ConfigReadableCoreTest.kt : org.json JVM 无 similar() ──
# Android 的 JSONObject.similar 在 JVM org.json 不存在;改为逐字段递归比较。
edit(r'config\readable\ConfigReadableCoreTest.kt', [
    ('        assertEquals(JSONObject(ios).similar(JSONObject(decoded.jsonString())), true)',
     '        assertJsonEqual(JSONObject(ios), JSONObject(decoded.jsonString()))',
     'similar() -> assertJsonEqual (第237行)'),
])
# 在文件末尾类闭合前插入辅助函数(放在类内最后一个 } 之前)
p = B + r'\config\readable\ConfigReadableCoreTest.kt'
with io.open(p, 'r', encoding='utf-8') as f:
    s = f.read()
helper = '''
    /** JVM org.json 没有 Android 的 similar();按结构递归比较,忽略 key 顺序。 */
    private fun assertJsonEqual(a: Any?, b: Any?, path: String = "$") {
        when (a) {
            is JSONObject -> {
                assertTrue("$path expected object", b is JSONObject)
                val bo = b as JSONObject
                assertEquals("$path length", a.length(), bo.length())
                for (k in a.keys()) assertTrue("$path.$k", bo.has(k) && run {
                    assertJsonEqual(a.get(k), bo.get(k), "$path.$k"); true
                })
            }
            is JSONArray -> {
                assertTrue("$path expected array", b is JSONArray)
                val ba = b as JSONArray
                assertEquals("$path length", a.length(), ba.length())
                for (i in 0 until a.length()) assertJsonEqual(a.get(i), ba.get(i), "$path[$i]")
            }
            is Number -> assertEquals(path, a.toDouble(), (b as Number).toDouble(), 0.0)
            else -> assertEquals(path, a, b)
        }
    }
'''
idx = s.rfind('\n}')
if idx < 0:
    print('  [FAIL] ConfigReadableCoreTest: 找不到类结尾'); sys.exit(1)
s = s[:idx] + '\n' + helper + s[idx:]
with io.open(p, 'w', encoding='utf-8', newline='') as f:
    f.write(s)
print('  [OK] ConfigReadableCoreTest: 插入 assertJsonEqual 辅助函数')

# ── 4) NestedToolSchemaTest.kt : similar() ──
edit(r'provider\NestedToolSchemaTest.kt', [
    ('            assertTrue("$actual", JSONObject(expected).similar(actual))',
     '            assertJsonEqual(JSONObject(expected), actual)',
     'similar() -> assertJsonEqual (第169行)'),
])
p = B + r'\provider\NestedToolSchemaTest.kt'
with io.open(p, 'r', encoding='utf-8') as f:
    s = f.read()
idx = s.rfind('\n}')
if idx < 0:
    print('  [FAIL] NestedToolSchemaTest: 找不到类结尾'); sys.exit(1)
s = s[:idx] + '\n' + helper + s[idx:]
with io.open(p, 'w', encoding='utf-8', newline='') as f:
    f.write(s)
print('  [OK] NestedToolSchemaTest: 插入 assertJsonEqual 辅助函数')

# ── 5) CloudAsrProtocolsTest.kt : keySet() (JVM org.json 只有 keys():Iterator) ──
edit(r'provider\voice\CloudAsrProtocolsTest.kt', [
    ('                assertEquals("$path keys", expected.keySet(), actual.keySet())\n'
     '                for (k in expected.keySet()) assertJson(expected.get(k), actual.get(k), "$path.$k")',
     '                val expKeys = expected.keys().asSequence().toSet()\n'
     '                val actKeys = actual.keys().asSequence().toSet()\n'
     '                assertEquals("$path keys", expKeys, actKeys)\n'
     '                for (k in expKeys) assertJson(expected.get(k), actual.get(k), "$path.$k")',
     'keySet() -> keys().asSequence().toSet() (第39-40行,含3处)'),
])

print('\n全部 9 处错误修复完成(5 个文件)。')
