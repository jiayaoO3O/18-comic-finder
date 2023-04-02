from jmcomic import *

jm_albums = str_to_list('''
JM438251
''')

def get_option():
    option = create_option('../assets/config/workflow_config.yml')
    return option

download_album(jm_albums, option=get_option())