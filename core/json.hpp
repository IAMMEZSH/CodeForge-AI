#pragma once

#include <cctype>
#include <cmath>
#include <cstdint>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

class Json {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Type type = Type::Null;
    bool boolean = false;
    double number = 0.0;
    std::string text;
    std::vector<Json> arr;
    std::map<std::string, Json> obj;

    Json() = default;
    Json(std::nullptr_t) {}
    Json(bool value) : type(Type::Bool), boolean(value) {}
    Json(int value) : type(Type::Number), number((double)value) {}
    Json(unsigned value) : type(Type::Number), number((double)value) {}
    Json(long value) : type(Type::Number), number((double)value) {}
    Json(long long value) : type(Type::Number), number((double)value) {}
    Json(double value) : type(Type::Number), number(value) {}
    Json(float value) : type(Type::Number), number((double)value) {}
    Json(const char* value) : type(Type::String), text(value != nullptr ? value : "") {}
    Json(const std::string& value) : type(Type::String), text(value) {}
    Json(std::string&& value) : type(Type::String), text(std::move(value)) {}

    static Json make_object() {
        Json value;
        value.type = Type::Object;
        return value;
    }

    static Json make_array() {
        Json value;
        value.type = Type::Array;
        return value;
    }

    bool is_null() const { return type == Type::Null; }
    bool is_object() const { return type == Type::Object; }
    bool is_array() const { return type == Type::Array; }
    bool is_string() const { return type == Type::String; }
    bool is_number() const { return type == Type::Number; }
    bool is_bool() const { return type == Type::Bool; }

    Json& operator[](const std::string& key) {
        if (type != Type::Object) {
            type = Type::Object;
            obj.clear();
        }
        return obj[key];
    }

    const Json* find(const std::string& key) const {
        if (type != Type::Object) {
            return nullptr;
        }
        auto it = obj.find(key);
        return it == obj.end() ? nullptr : &it->second;
    }

    std::string get_string(const std::string& key, const std::string& fallback = "") const {
        const Json* value = find(key);
        return value != nullptr && value->is_string() ? value->text : fallback;
    }

    int get_int(const std::string& key, int fallback = 0) const {
        const Json* value = find(key);
        if (value == nullptr) {
            return fallback;
        }
        if (value->is_number()) {
            return (int)value->number;
        }
        if (value->is_string()) {
            try {
                return std::stoi(value->text);
            } catch (...) {
                return fallback;
            }
        }
        return fallback;
    }

    double get_double(const std::string& key, double fallback = 0.0) const {
        const Json* value = find(key);
        if (value != nullptr && value->is_number()) {
            return value->number;
        }
        return fallback;
    }

    bool get_bool(const std::string& key, bool fallback = false) const {
        const Json* value = find(key);
        return value != nullptr && value->is_bool() ? value->boolean : fallback;
    }

    std::string as_string() const {
        if (type == Type::String) {
            return text;
        }
        if (type == Type::Number) {
            std::ostringstream out;
            if (std::floor(number) == number && std::fabs(number) < 1e15) {
                out << (long long)number;
            } else {
                out << number;
            }
            return out.str();
        }
        if (type == Type::Bool) {
            return boolean ? "true" : "false";
        }
        return dump();
    }

    int as_int(int fallback = 0) const {
        if (type == Type::Number) {
            return (int)number;
        }
        if (type == Type::String) {
            try {
                return std::stoi(text);
            } catch (...) {
                return fallback;
            }
        }
        return fallback;
    }

    static Json parse(const std::string& source, std::string* error = nullptr) {
        try {
            Parser parser(source);
            Json result = parser.parse_value();
            parser.skip_ws();
            if (!parser.at_end()) {
                throw std::runtime_error("unexpected trailing characters");
            }
            return result;
        } catch (const std::exception& ex) {
            if (error != nullptr) {
                *error = ex.what();
            }
            return Json();
        }
    }

    std::string dump() const {
        std::string out;
        write(out);
        return out;
    }

private:
    class Parser {
    public:
        explicit Parser(const std::string& source) : src_(source) {}

        bool at_end() const { return pos_ >= src_.size(); }

        void skip_ws() {
            while (pos_ < src_.size()) {
                char c = src_[pos_];
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    ++pos_;
                } else {
                    break;
                }
            }
        }

        Json parse_value() {
            skip_ws();
            if (at_end()) {
                throw std::runtime_error("unexpected end of json");
            }
            char c = src_[pos_];
            if (c == '{') return parse_object();
            if (c == '[') return parse_array();
            if (c == '"') return Json(parse_string());
            if (c == 't') return parse_literal("true", Json(true));
            if (c == 'f') return parse_literal("false", Json(false));
            if (c == 'n') return parse_literal("null", Json());
            if (c == '-' || (c >= '0' && c <= '9')) return parse_number();
            throw std::runtime_error("unexpected character in json");
        }

    private:
        Json parse_object() {
            Json result = Json::make_object();
            ++pos_;
            skip_ws();
            if (pos_ < src_.size() && src_[pos_] == '}') {
                ++pos_;
                return result;
            }
            while (true) {
                skip_ws();
                if (pos_ >= src_.size() || src_[pos_] != '"') {
                    throw std::runtime_error("expected object key");
                }
                std::string key = parse_string();
                skip_ws();
                if (pos_ >= src_.size() || src_[pos_] != ':') {
                    throw std::runtime_error("expected ':'");
                }
                ++pos_;
                result.obj[key] = parse_value();
                skip_ws();
                if (pos_ >= src_.size()) {
                    throw std::runtime_error("unterminated object");
                }
                if (src_[pos_] == ',') {
                    ++pos_;
                    continue;
                }
                if (src_[pos_] == '}') {
                    ++pos_;
                    return result;
                }
                throw std::runtime_error("expected ',' or '}'");
            }
        }

        Json parse_array() {
            Json result = Json::make_array();
            ++pos_;
            skip_ws();
            if (pos_ < src_.size() && src_[pos_] == ']') {
                ++pos_;
                return result;
            }
            while (true) {
                result.arr.push_back(parse_value());
                skip_ws();
                if (pos_ >= src_.size()) {
                    throw std::runtime_error("unterminated array");
                }
                if (src_[pos_] == ',') {
                    ++pos_;
                    continue;
                }
                if (src_[pos_] == ']') {
                    ++pos_;
                    return result;
                }
                throw std::runtime_error("expected ',' or ']'");
            }
        }

        Json parse_literal(const char* literal, const Json& value) {
            size_t len = std::char_traits<char>::length(literal);
            if (src_.compare(pos_, len, literal) != 0) {
                throw std::runtime_error("invalid literal");
            }
            pos_ += len;
            return value;
        }

        Json parse_number() {
            size_t start = pos_;
            if (src_[pos_] == '-') {
                ++pos_;
            }
            while (pos_ < src_.size() && std::isdigit((unsigned char)src_[pos_])) {
                ++pos_;
            }
            if (pos_ < src_.size() && src_[pos_] == '.') {
                ++pos_;
                while (pos_ < src_.size() && std::isdigit((unsigned char)src_[pos_])) {
                    ++pos_;
                }
            }
            if (pos_ < src_.size() && (src_[pos_] == 'e' || src_[pos_] == 'E')) {
                ++pos_;
                if (pos_ < src_.size() && (src_[pos_] == '+' || src_[pos_] == '-')) {
                    ++pos_;
                }
                while (pos_ < src_.size() && std::isdigit((unsigned char)src_[pos_])) {
                    ++pos_;
                }
            }
            std::string token = src_.substr(start, pos_ - start);
            if (token.empty()) {
                throw std::runtime_error("invalid number");
            }
            try {
                return Json(std::stod(token));
            } catch (...) {
                throw std::runtime_error("invalid number");
            }
        }

        std::string parse_string() {
            ++pos_;
            std::string out;
            while (pos_ < src_.size()) {
                unsigned char c = (unsigned char)src_[pos_++];
                if (c == '"') {
                    return out;
                }
                if (c != '\\') {
                    out.push_back((char)c);
                    continue;
                }
                if (pos_ >= src_.size()) {
                    break;
                }
                char esc = src_[pos_++];
                switch (esc) {
                    case '"': out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    case 'u': {
                        if (pos_ + 4 > src_.size()) {
                            throw std::runtime_error("invalid unicode escape");
                        }
                        unsigned code = 0;
                        for (int i = 0; i < 4; ++i) {
                            char h = src_[pos_++];
                            code <<= 4;
                            if (h >= '0' && h <= '9') code |= (unsigned)(h - '0');
                            else if (h >= 'a' && h <= 'f') code |= (unsigned)(h - 'a' + 10);
                            else if (h >= 'A' && h <= 'F') code |= (unsigned)(h - 'A' + 10);
                            else throw std::runtime_error("invalid unicode escape");
                        }
                        append_utf8(out, (uint32_t)code);
                        break;
                    }
                    default:
                        throw std::runtime_error("invalid escape");
                }
            }
            throw std::runtime_error("unterminated string");
        }

        static void append_utf8(std::string& out, uint32_t code) {
            if (code <= 0x7F) {
                out.push_back((char)code);
            } else if (code <= 0x7FF) {
                out.push_back((char)(0xC0 | (code >> 6)));
                out.push_back((char)(0x80 | (code & 0x3F)));
            } else {
                out.push_back((char)(0xE0 | (code >> 12)));
                out.push_back((char)(0x80 | ((code >> 6) & 0x3F)));
                out.push_back((char)(0x80 | (code & 0x3F)));
            }
        }

        const std::string& src_;
        size_t pos_ = 0;
    };

    static void write_escaped(const std::string& value, std::string& out) {
        static const char* hex = "0123456789abcdef";
        out.push_back('"');
        for (unsigned char c : value) {
            switch (c) {
                case '"': out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\b': out += "\\b"; break;
                case '\f': out += "\\f"; break;
                case '\n': out += "\\n"; break;
                case '\r': out += "\\r"; break;
                case '\t': out += "\\t"; break;
                default:
                    if (c < 0x20) {
                        out += "\\u00";
                        out.push_back(hex[(c >> 4) & 0x0F]);
                        out.push_back(hex[c & 0x0F]);
                    } else {
                        out.push_back((char)c);
                    }
            }
        }
        out.push_back('"');
    }

    void write(std::string& out) const {
        switch (type) {
            case Type::Null:
                out += "null";
                break;
            case Type::Bool:
                out += boolean ? "true" : "false";
                break;
            case Type::Number: {
                if (!std::isfinite(number)) {
                    out += "null";
                    break;
                }
                if (std::floor(number) == number && std::fabs(number) < 9.0e15) {
                    out += std::to_string((long long)number);
                } else {
                    std::ostringstream stream;
                    stream.precision(15);
                    stream << number;
                    out += stream.str();
                }
                break;
            }
            case Type::String:
                write_escaped(text, out);
                break;
            case Type::Array: {
                out.push_back('[');
                for (size_t i = 0; i < arr.size(); ++i) {
                    if (i > 0) out.push_back(',');
                    arr[i].write(out);
                }
                out.push_back(']');
                break;
            }
            case Type::Object: {
                out.push_back('{');
                bool first = true;
                for (const auto& pair : obj) {
                    if (!first) out.push_back(',');
                    first = false;
                    write_escaped(pair.first, out);
                    out.push_back(':');
                    pair.second.write(out);
                }
                out.push_back('}');
                break;
            }
        }
    }
};
