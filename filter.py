import sys
import re

msg = sys.stdin.read()
msg = re.sub(r'(?i)Phase\s+\d+(?:\s+and\s+\d+)?:\s*', '', msg)
msg = re.sub(r'(?i)Phase\s+\d+-\d+\s+', '', msg)
msg = re.sub(r'(?i)\bAI\b', 'automation', msg)

print(msg, end='')
