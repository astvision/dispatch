import type { ReactNode } from "react";
import { STATIONS } from "./tickets";

/** One topic of the manual: a heading over a few short paragraphs or a command list. */
function Topic({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="guide-topic">
      <h2>{title}</h2>
      {children}
    </section>
  );
}

const COMMANDS: [string, string][] = [
  ["/task төсөл тайлбар", "Шинэ даалгавар; төслийг шууд нэрлэж болно"],
  ["/status", "Одоо юу хийж байна, чухал байдлыг өөрчлөх"],
  ["/history, /history N", "Дууссан даалгаврууд; N-ийн явц"],
  ["/cancel N", "Даалгавар цуцлах"],
  ["/retry N", "Амжилтгүй алхмыг дахин оролдох"],
  ["/projects", "Төслүүд, бэлэн эсэх"],
  ["/stats", "Статистик"],
  ["/new", "Туслахтай шинэ яриа эхлүүлэх"],
  ["/manage", "Энэ хуудсыг нээх"],
];

/** How to use Dispatch, for someone who has just opened it: the chat, the tickets on Home, and the commands. */
export default function GuidePage() {
  return (
    <article className="guide">
      <h1 className="mini-title">Гарын авлага</h1>

      <Topic title="Даалгавар өгөх">
        <p>Ботын хувийн чатад юу хийлгэхээ энгийнээр бичнэ. Туслах тань хариулж, даалгавар үүсгэх бол доор нь
          <b> ✅</b> товч гаргана; товч дарахаас нааш юу ч өөрчлөгдөхгүй.</p>
        <p><code>/task</code> командаар шууд өгвөл төсөл, чухал байдлыг (🔴 🟡 🟢) товчоор асууна. Зураг, файлыг
          тайлбартай нь илгээвэл агент уншина.</p>
      </Topic>

      <Topic title="Даалгаврын зам">
        <p className="guide-route">{STATIONS.map((name, index) => `${index + 1} ${name}`).join(" › ")}</p>
        <p>Агент эхлээд төлөвлөгөө гаргана. Асуулт байвал нэг нэгээр нь асууна: санал болгосон хариултын нэгийг
          сонгох, <b>Өөрөөр</b> гэж өөрийн үгээр бичих, эсвэл <b>Та шийд</b> гэж агентад даатгана.</p>
        <p>Төлөвлөгөөн дээр <b>Зөвшөөрөх</b> дарвал агент хэрэгжүүлж, draft PR-ын холбоосыг илгээнэ. Төлөвлөгөөнд хариу
          бичвэл засвар, үр дүнд хариу бичвэл нэмэлт хүсэлт болно.</p>
      </Topic>

      <Topic title="Нүүр хуудас">
        <p><b>Таны шийдвэр</b> — таныг хүлээж буй даалгаврууд. Товшоод төлөвлөгөөг уншиж, асуултад хариулж,
          зөвшөөрөх эсвэл татгалзана.</p>
        <p><b>Явж байна</b> — ажиллаж буй даалгаврууд, сүүлийн алхам, цагтай нь.</p>
        <p><b>Дууссан</b> — сүүлд дууссан хэдэн даалгавар. <b>Бүх даалгавраа харах</b> бүх жагсаалтыг нээнэ.</p>
        <p>Мөр бүрийн өнгөт цэг, үг хоёр төлөвийг нь хэлнэ. Дээрх хайлтаар гарчиг, төсөл эсвэл #дугаараар хайна.</p>
        <p>Энэ хуудсыг чатын доод талын <b>Удирдах</b> товчоор эсвэл <code>/manage</code>-ээр нээнэ.</p>
      </Topic>

      <Topic title="Группт">
        <p>Группт <code>/task@бот текст</code> эсвэл ботыг дурдсан мессеж бичвэл ноорог таны хувийн чатад
          нээгдэнэ. Группт юу гарахыг <b>Миний тохиргоо</b>-оос сонгоно.</p>
      </Topic>

      <Topic title="Командууд">
        <dl className="guide-commands">
          {COMMANDS.map(([command, meaning]) => (
            <div key={command}><dt><code>{command}</code></dt><dd>{meaning}</dd></div>
          ))}
        </dl>
      </Topic>
    </article>
  );
}
