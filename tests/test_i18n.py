from core.i18n import (
    t,
    get_current_locale,
    set_current_locale,
    get_supported_languages,
    get_client_translations,
    SUPPORTED_LANGUAGES,
)
from core.db import get_user_settings, update_user_settings, get_db


def test_locale_helpers(flask_app):
    with flask_app.app.test_request_context('/?lang=zh_TW'):
        assert get_current_locale() == 'zh_TW'
    with flask_app.app.test_request_context('/'):
        set_current_locale('ms')
        assert get_current_locale() == 'ms'


def test_t_function_translations():
    # 1. 默认与简体中文
    assert t('nav.dashboard', lang='zh') == '仪表盘'
    assert t('common.save', lang='zh') == '保存'

    # 2. 英语
    assert t('nav.dashboard', lang='en') == 'Dashboard'
    assert t('common.save', lang='en') == 'Save'

    # 3. 马来语
    assert t('nav.dashboard', lang='ms') == 'Papan Pemuka'
    assert t('common.save', lang='ms') == 'Simpan'

    # 4. 繁体中文
    assert t('nav.dashboard', lang='zh_TW') == '儀表盤'
    assert t('common.save', lang='zh_TW') == '保存'


def test_t_function_fallbacks_and_formatting():
    # Fallback to zh if missing in target lang
    assert t('unknown.dummy.key', default='Default Value', lang='en') == 'Default Value'
    assert t('unknown.dummy.key', lang='en') == 'unknown.dummy.key'

    # Variable interpolation
    from core.i18n import TRANSLATIONS
    TRANSLATIONS['zh']['test.greeting'] = '你好，{name}！'
    TRANSLATIONS['en']['test.greeting'] = 'Hello, {name}!'

    assert t('test.greeting', lang='zh', name='Alice') == '你好，Alice！'
    assert t('test.greeting', lang='en', name='Bob') == 'Hello, Bob!'


def test_supported_languages_list():
    langs = get_supported_languages()
    codes = [l['code'] for l in langs]
    assert 'zh' in codes
    assert 'en' in codes
    assert 'ms' in codes
    assert 'zh_TW' in codes
    assert len(langs) == len(SUPPORTED_LANGUAGES)


def test_client_translations():
    client_dict_zh = get_client_translations('zh')
    assert client_dict_zh['nav.dashboard'] == '仪表盘'

    client_dict_en = get_client_translations('en')
    assert client_dict_en['nav.dashboard'] == 'Dashboard'


def test_user_settings_language_persistence(flask_app, admin_user_id):
    with flask_app.app.app_context():
        db = get_db()
        # 初始默认应为 zh
        settings = get_user_settings(admin_user_id, db=db)
        assert settings.get('language') in ('zh', None, '')

        # 更新为 en
        update_user_settings(admin_user_id, {'language': 'en'}, db=db)
        updated = get_user_settings(admin_user_id, db=db)
        assert updated['language'] == 'en'

        # 恢复为 zh
        update_user_settings(admin_user_id, {'language': 'zh'}, db=db)
        restored = get_user_settings(admin_user_id, db=db)
        assert restored['language'] == 'zh'


def test_api_set_language_endpoint(flask_app, logged_in_client, admin_user_id):
    # 1. 切换到 en
    res = logged_in_client.get('/api/set-language?lang=en')
    assert res.status_code == 302
    assert 'lang=en' in res.headers.get('Set-Cookie', '')

    with logged_in_client.session_transaction() as sess:
        assert sess.get('lang') == 'en'

    # 验证数据库也同步更新了
    with flask_app.app.app_context():
        db = get_db()
        s = get_user_settings(admin_user_id, db=db)
        assert s['language'] == 'en'

    # 2. POST JSON 切换到 ms
    res_json = logged_in_client.post(
        '/api/set-language',
        json={'language': 'ms'}
    )
    assert res_json.status_code == 200
    assert res_json.get_json()['ok'] is True
    assert res_json.get_json()['language'] == 'ms'

    # 3. 页面渲染验证当前语言
    res_page = logged_in_client.get('/')
    assert res_page.status_code == 200
    html = res_page.get_data(as_text=True)
    # 应展示马来文
    assert 'Papan Pemuka' in html or 'Buku Lejar' in html

    # 4. 恢复为 zh
    logged_in_client.get('/api/set-language?lang=zh')
