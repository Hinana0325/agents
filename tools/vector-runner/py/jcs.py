"""RFC 8785 JCS 规范化（本项目子集：无浮点数）。"""


def _escape(s: str) -> str:
    out = ['"']
    for ch in s:
        o = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == '\\':
            out.append('\\\\')
        elif ch == '\b':
            out.append('\\b')
        elif ch == '\t':
            out.append('\\t')
        elif ch == '\n':
            out.append('\\n')
        elif ch == '\f':
            out.append('\\f')
        elif ch == '\r':
            out.append('\\r')
        elif o < 0x20:
            out.append('\\u%04x' % o)
        else:
            out.append(ch)
    out.append('"')
    return ''.join(out)


def canonicalize(value) -> str:
    if value is None:
        return 'null'
    if value is True:
        return 'true'
    if value is False:
        return 'false'
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        raise ValueError('floats are not allowed in envelopes')
    if isinstance(value, str):
        return _escape(value)
    if isinstance(value, list):
        return '[' + ','.join(canonicalize(v) for v in value) + ']'
    if isinstance(value, dict):
        for k in value:
            if not isinstance(k, str):
                raise ValueError('object keys must be strings')
        keys = sorted(value.keys(), key=lambda k: k.encode('utf-16-be'))
        return '{' + ','.join(_escape(k) + ':' + canonicalize(value[k]) for k in keys) + '}'
    raise TypeError('unsupported type: %r' % type(value))
