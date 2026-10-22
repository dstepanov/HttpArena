import sys,collections
f=sys.argv[1]; tot=0; selfc=collections.Counter(); incl=collections.Counter(); fw=collections.Counter()
for line in open(f):
    st,_,n=line.rpartition(' '); n=int(n); tot+=n
    fr=st.split(';'); selfc[fr[-1]]+=n
    seen=set(fr)
    for x in seen: incl[x]+=n
    # first framework-side frame from the leaf
    for x in reversed(fr):
        if x.startswith(('io/micronaut','httparena','views/')) :
            fw[x]+=n; break
    else: fw['(no micronaut/entry frame)']+=n
print('total',tot)
print('-- top self'); [print(f'{100*c/tot:5.1f}% {k}') for k,c in selfc.most_common(8)]
print('-- top inclusive micronaut/entry/netty'); 
for k,c in [(k,c) for k,c in incl.most_common() if k.startswith(('io/micronaut','httparena','io/netty/handler/codec','views/'))][:10]: print(f'{100*c/tot:5.1f}% {k}')
print('-- leaf-most framework frame (including callees)')
for k,c in fw.most_common(8): print(f'{100*c/tot:5.1f}% {k}')
