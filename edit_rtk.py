import sys
filepath = r'D:\code\rtklib_java\src\main\java\org\rtklib\java\data\Rtk.java'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()
old = '    public Ambc ambc;'
new = '''    public Ambc ambc;

    public int initial_mode;

    public int epoch;'''
content = content.replace(old, new, 1)
old_init = '        this.ambc = new Ambc();'
new_init = '''        this.ambc = new Ambc();
        this.initial_mode = 0;
        this.epoch = 0;'''
content = content.replace(old_init, new_init, 1)
with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
print('Rtk.java updated successfully')
