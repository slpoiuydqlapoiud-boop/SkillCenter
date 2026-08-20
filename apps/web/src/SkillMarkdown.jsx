import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export function skillMarkdownBody(content = "") {
  return String(content)
    .replace(/^\uFEFF?---\r?\n[\s\S]*?\r?\n---(?:\r?\n|$)/, "")
    .trimStart();
}

export function SkillMarkdown({ content = "" }) {
  const body = skillMarkdownBody(content);
  return <section className="skill-markdown"><div className="section-heading"><div><h2>SKILL.md</h2><p>Skill 使用说明与执行约定</p></div></div>{body ? <article className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml>{body}</ReactMarkdown></article> : <div className="markdown-empty">该版本没有可展示的 SKILL.md 内容</div>}</section>;
}
