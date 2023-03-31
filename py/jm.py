import jmcomic # 导入此模块，需要先通过pip安装.
import sys

sys.path.append("18-comic-finder/py/")

jmcomic.option(f'./py/jmcomic_config_no_proxy.yml')
jmcomic.download_album('430740')
# jmcomic.option(f'./py/jmcomic_config_no_proxy.yml')
