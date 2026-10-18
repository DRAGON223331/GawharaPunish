# GawharaPunish

قايمة دايرية (GUI) لأوامر الأدمن والعقوبات: `/punish <لاعب>`

- اختار العقوبة من الدايرة، وبعدها السبب (جاهز أو مخصص بالكتابة).
- مدمج: تحذير، ميوت بمدة، فك ميوت، تجميد، انتقال، سحب، فتح إنفنتوري/إندر شيست.
- باقي العقوبات (kick/ban/tempban) بتتنفذ كأوامر كونسول وتتغير من config.yml.

الصلاحيات: punish.use, punish.exempt, punish.notify, punish.reload, punish.spectate
`/punish history <لاعب>` يعرض سجل عقوبات اللاعب (بيتحفظ في history.yml).
البناء: JDK 25 + Maven → `mvn clean package` → target/GawharaPunish.jar

المراقبة: `/punish spectate <لاعب>` (أو زرار "راقب اللاعب" في القايمة)، `/punish view` لتغيير المنظور (من عيونه / من ورا اللاعب / كاميرا حرة)، `/punish unspectate` للرجوع لمكانك.
