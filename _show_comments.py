import json
cs = json.load(open(r'D:\ideaPro\agentscope-java\issue3278_comments.json', encoding='utf-8'))
print(f'total comments: {len(cs)}')
for c in cs:
    print("---", c['user']['login'], "@", c['created_at'], "---")
    print(c['body'][:1200])
    print()
